(ns metabase.serialization.dump
  ""
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [metabase.automagic-dashboards.filters :refer [field-reference?]]
            [metabase.models
             [card :refer [Card]]
             [collection :refer [Collection]]
             [dashboard :refer [Dashboard]]
             [dashboard-card :refer [DashboardCard]]
             [dashboard-card-series :refer [DashboardCardSeries]]
             [database :refer [Database]]
             [field :refer [Field]]
             [metric :refer [Metric]]
             [segment :refer [Segment]]
             [table :refer [Table]]]
            [metabase.query-processor.util :as qp.util]
            [metabase.util :as u]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]
            [yaml.core :as yaml]))

;; We replace the IDs in MBQL with full paths to make diffs more meaningful

(defmulti
  ^{:doc      ""
    :private  true
    :arglists '([prefix entity])}
  fully-qualified-name (fn [_ entity]
                         (type entity)))

(defmethod fully-qualified-name (type Database)
  [prefix db]
  (str prefix "/databases/" (:name db)))

(defmethod fully-qualified-name (type Table)
  [prefix table]
  (format "%s/schemas/%s/tables/%s"
          (->> table :db_id Database (fully-qualified-name prefix))
          (:schema table)
          (:name table)))

(defmethod fully-qualified-name (type Field)
  [prefix field]
  (str (->> field :table_id Table (fully-qualified-name prefix)) "/fields/" (:name field)))

(defmethod fully-qualified-name (type Metric)
  [prefix metric]
  (str (->> metric :table_id Table (fully-qualified-name prefix)) "/metrics/" (:name metric)))

(defmethod fully-qualified-name (type Segment)
  [prefix segment]
  (str (->> segment :table_id Table (fully-qualified-name prefix)) "/segments/" (:name segment)))

(defmethod fully-qualified-name (type Collection)
  [prefix collection]
  (let [parents (if (= (:location collection) "/")
                  ""
                  (->> (str/split (:location collection) #"/")
                       rest
                       (map (fn [parent]
                              (let [parent (Collection (Integer/parseInt parent))]
                                (str (:name parent) "/collections"))))
                       (str/join "/")
                       (format "%s/")))]
    (str prefix "/collections/" parents (:name collection))))

(defmethod fully-qualified-name (type Dashboard)
  [prefix dashboard]
  (format "%s/dashboards/%s"
          (or (some->> dashboard :collection_id Collection (fully-qualified-name prefix))
              (str prefix "/collections"))
          (:name dashboard)))

(defmethod fully-qualified-name (type Card)
  [prefix card]
  (format "%s/cards/%s"
          (or (some->> card
                       :dataset_query
                       qp.util/query->source-card-id
                       Card
                       (fully-qualified-name prefix))
              (some->> card
                       :collection_id
                       Collection
                       (fully-qualified-name prefix))
              (str prefix "/collections"))
          (:name card)))

(def ^:private SegmentOrMetric
  [(s/one (s/constrained su/KeywordOrString
                         (comp #{:metric :segment} qp.util/normalize-token))
          "head")
   (s/cond-pre s/Int su/KeywordOrString)])

(def ^{:arglists '([form])} entity-reference?
  "Is given form an MBQL entity reference?"
  (some-fn field-reference? (complement (s/checker SegmentOrMetric))))

(defn- entity-reference->fully-qualified-name
  [prefix [op & args :as entity-reference]]
  (case (qp.util/normalize-token op)
    :metric        [:metric (fully-qualified-name prefix (Metric (first args)))]
    :segment       [:segment (fully-qualified-name prefix (Segment (first args)))]
    :field-id      [:field-id (if (string? (first args))
                                (first args)
                                (fully-qualified-name prefix (Field (first args))))]
    :fk->          (into [:fk->]
                         (for [arg args]
                           (if (number? arg)
                             (Field arg)
                             (entity-reference->fully-qualified-name prefix arg))))
    :field-literal entity-reference))

(defn- humanize-entity-references
  [prefix entity]
  (walk/postwalk
   (fn [form]
     (cond
       (entity-reference? form)
       (entity-reference->fully-qualified-name prefix form)

       (map? form)
       (let [fully-qualified-name (fn [entity-id model]
                                    (if (string? entity-id)
                                      (fully-qualified-name prefix (model entity))))]
         (-> form
             (u/update-when :database (fn [db]
                                        (if (= db -1337)
                                          "database/virtual"
                                          (fully-qualified-name db Database))))
             (u/update-when :card_id comp fully-qualified-name Card)
             (u/update-when :source-table (fn [source-table]
                                            (if (and (string? source-table)
                                                     (str/starts-with? source-table "card__"))
                                              (-> source-table
                                                  (str/split #"__")
                                                  second
                                                  Integer/parseInt
                                                  (fully-qualified-name Card))
                                              (fully-qualified-name source-table Table))))))

       :else
       form))
   entity))

(defmulti
  ^{:doc      ""
    :arglists '([dir entity])}
  dump (fn [_ entity]
         (type entity)))

(defn- strip-crud
  [entity]
  (dissoc entity :id :creator_id :created_at :updated_at :db_id :database_id
          :card_id :dashboard_id :fields_hash :personal_owner_id :made_public_by_id :collection_id))

(defn- spit-yaml
  ([path entity] (spit-yaml path :dir entity))
  ([path mode entity]
   (let [fname (if (= mode :dir)
                 (format "%s/%s.yaml" (fully-qualified-name path entity) (:name entity))
                 (str (fully-qualified-name path entity) ".yaml"))]
     (io/make-parents fname)
     (spit fname (yaml/generate-string (strip-crud entity) :dumper-options {:flow-style :block})))))

(defmethod dump (type Database)
  [path db]
  (spit-yaml path (dissoc db :features)))

(defmethod dump (type Table)
  [path table]
  (spit-yaml path table))

(defmethod dump (type Field)
  [path field]
  (spit-yaml path :file field))

(defmethod dump (type Segment)
  [path segment]
  (->> segment
       (humanize-entity-references path)
       (spit-yaml path :file)))

(defmethod dump (type Metric)
  [path metric]
  (->> metric
       (humanize-entity-references path)
       (spit-yaml path :file)))

(defn- dashboard-cards-for-dashboard
  [path dashboard]
  (->> dashboard
       u/get-id
       (db/select DashboardCard :dashboard_id)
       (map (fn [dashboard-card]
              (->> (assoc dashboard-card
                     :series (for [series (db/select DashboardCardSeries
                                            :dashboardcard_id (u/get-id dashboard-card))]
                               (-> series
                                   (update :card_id (comp (partial fully-qualified-name path) Card))
                                   (dissoc :id :dashboardcard_id))))
                   strip-crud
                   (humanize-entity-references path))))))

(defmethod dump (type Dashboard)
  [path dashboard]
  (spit-yaml path :file (assoc dashboard
                          :dashboard_cards (dashboard-cards-for-dashboard path dashboard))))

(defmethod dump (type Collection)
  [path collection]
  (spit-yaml path collection))

(defmethod dump (type Card)
  [path card]
  (->> card
       (humanize-entity-references path)
       (spit-yaml path :debug)))
