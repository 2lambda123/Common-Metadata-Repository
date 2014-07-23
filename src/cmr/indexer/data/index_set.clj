(ns cmr.indexer.data.index-set
  (:require [cmr.common.lifecycle :as lifecycle]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.common.services.errors :as errors]
            [cmr.common.concepts :as cs]
            [cmr.transmit.metadata-db :as meta-db]
            [cmr.transmit.index-set :as index-set]
            [cmr.transmit.config :as transmit-config]
            [cmr.transmit.connection :as transmit-conn]
            [cmr.common.cache :as cache]
            [cmr.system-trace.core :refer [deftracefn]]))

(def collection-setting {:index
                         {:number_of_shards 6,
                          :number_of_replicas 1,
                          :refresh_interval "1s"}})

;; TODO verify that all these options are necessary
(def string-field-mapping
  {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "no"})

(def date-field-mapping
  {:type "date" :format "yyyy-MM-dd'T'HH:mm:ssZ||yyyy-MM-dd'T'HH:mm:ss.SSSZ"})

(def double-field-mapping
  {:type "double"})

(def int-field-mapping
  {:type "integer"})

(def bool-field-mapping
  {:type "boolean"})

(defn stored
  "modifies a mapping to indicate that it should be stored"
  [field-mapping]
  (assoc field-mapping :store "yes"))

(defn not-indexed
  "modifies a mapping to indicate that it should not be indexed and thus is not searchable."
  [field-mapping]
  (assoc field-mapping :index "no"))

(def attributes-field-mapping
  "Defines mappings for attributes."
  {:type "nested"
   :dynamic "strict"
   :properties
   {:name string-field-mapping
    :string-value string-field-mapping
    :string-value.lowercase string-field-mapping
    :float-value double-field-mapping
    :int-value int-field-mapping
    :datetime-value date-field-mapping
    :time-value date-field-mapping
    :date-value date-field-mapping}})

(def science-keywords-field-mapping
  "Defines mappings for science keywords."
  {:type "nested"
   :dynamic "strict"
   :properties
   {:category string-field-mapping
    :category.lowercase string-field-mapping
    :topic string-field-mapping
    :topic.lowercase string-field-mapping
    :term string-field-mapping
    :term.lowercase string-field-mapping
    :variable-level-1 string-field-mapping
    :variable-level-1.lowercase string-field-mapping
    :variable-level-2 string-field-mapping
    :variable-level-2.lowercase string-field-mapping
    :variable-level-3 string-field-mapping
    :variable-level-3.lowercase string-field-mapping
    :detailed-variable string-field-mapping
    :detailed-variable.lowercase string-field-mapping}})

(def orbit-calculated-spatial-domain-mapping
  {:type "nested"
   :dynamic "strict"
   :properties {:orbit-number double-field-mapping
                :start-orbit-number double-field-mapping
                :stop-orbit-number double-field-mapping
                :equator-crossing-longitude double-field-mapping
                :equator-crossing-date-time date-field-mapping}})

(def spatial-shape-mapping
  "Defines the mapping for a single spatial shape. Each shape is represented by a minimum bounding
  rectangle, largest interior rectangle, and spatial ordinates."
  {:type "nested"
   :dynamic "strict"
   :properties {}})

(def spatial-coverage-fields
  "Defines the sets of fields shared by collections and granules for indexing spatial data."
  {;; Minimum Bounding Rectangle Fields
   ;; If a granule has multiple shapes then the MBR will cover all of the shapes
   :mbr-west double-field-mapping
   :mbr-north double-field-mapping
   :mbr-east double-field-mapping
   :mbr-south double-field-mapping
   :mbr-crosses-antimeridian bool-field-mapping

   ;; Largest Interior Rectangle Fields
   ;; If a granule has multiple shapes then the LR will be the largest in one of the shapes
   :lr-west double-field-mapping
   :lr-north double-field-mapping
   :lr-east double-field-mapping
   :lr-south double-field-mapping
   :lr-crosses-antimeridian bool-field-mapping

   ;; ords-info contains tuples of shapes stored in ords
   ;; Each tuple contains the shape type and the number of ordinates
   :ords-info (stored int-field-mapping)
   ;; ords contains longitude latitude pairs (ordinates) of all the shapes
   :ords (stored int-field-mapping)})

(def collection-mapping
  {:collection {:dynamic "strict",
                :_source {:enabled false},
                :_all {:enabled false},
                :_id   {:path "concept-id"},
                :_ttl {:enabled true},
                :properties (merge {:concept-id            (stored string-field-mapping)
                                    :entry-id           (stored string-field-mapping)
                                    :entry-id.lowercase string-field-mapping
                                    :entry-title           (stored string-field-mapping)
                                    :entry-title.lowercase string-field-mapping
                                    :provider-id           (stored string-field-mapping)
                                    :provider-id.lowercase string-field-mapping
                                    :short-name            (stored string-field-mapping)
                                    :short-name.lowercase  string-field-mapping
                                    :version-id            (stored string-field-mapping)
                                    :version-id.lowercase  string-field-mapping
                                    :revision-date         date-field-mapping
                                    :processing-level-id   (stored string-field-mapping)
                                    :processing-level-id.lowercase  string-field-mapping
                                    :collection-data-type  (stored string-field-mapping)
                                    :collection-data-type.lowercase  string-field-mapping
                                    :start-date            (stored date-field-mapping)
                                    :end-date              (stored date-field-mapping)
                                    :platform-sn           string-field-mapping
                                    :platform-sn.lowercase string-field-mapping
                                    :instrument-sn           string-field-mapping
                                    :instrument-sn.lowercase string-field-mapping
                                    :sensor-sn             string-field-mapping
                                    :sensor-sn.lowercase   string-field-mapping
                                    :project-sn            string-field-mapping
                                    :project-sn.lowercase  string-field-mapping
                                    :archive-center        (stored string-field-mapping)
                                    :archive-center.lowercase string-field-mapping
                                    :spatial-keyword        string-field-mapping
                                    :spatial-keyword.lowercase string-field-mapping
                                    :two-d-coord-name string-field-mapping
                                    :two-d-coord-name.lowercase string-field-mapping
                                    :attributes attributes-field-mapping
                                    :science-keywords science-keywords-field-mapping
                                    :downloadable (stored bool-field-mapping)
                                    ;; fields added for atom
                                    :browsable (not-indexed (stored bool-field-mapping))
                                    :atom-links (not-indexed (stored string-field-mapping))
                                    :summary (not-indexed (stored string-field-mapping))
                                    :original-format (not-indexed (stored string-field-mapping))
                                    :update-time (not-indexed (stored string-field-mapping))
                                    :associated-difs (stored string-field-mapping)
                                    :coordinate-system (not-indexed (stored string-field-mapping))}
                                   spatial-coverage-fields)}})

(def granule-setting {:index {:number_of_shards 6,
                              :number_of_replicas 1,
                              :refresh_interval "1s"}})

(def granule-mapping
  {:granule
   {:dynamic "strict",
    :_source { "enabled" false},
    :_all {"enabled" false},
    :_id  {:path "concept-id"},
    :_ttl {:enabled true},
    :properties (merge
                  {:concept-id            (stored string-field-mapping)
                   :collection-concept-id (stored string-field-mapping)

                   ;; fields added for atom
                   :entry-title (not-indexed (stored string-field-mapping))
                   :original-format (not-indexed (stored string-field-mapping))
                   :update-time (not-indexed (stored string-field-mapping))
                   :coordinate-system (not-indexed (stored string-field-mapping))

                   ;; Collection fields added strictly for sorting granule results
                   :entry-title.lowercase string-field-mapping
                   :short-name.lowercase  string-field-mapping
                   :version-id.lowercase  string-field-mapping

                   :provider-id           (stored string-field-mapping)
                   :provider-id.lowercase string-field-mapping

                   :granule-ur            (stored string-field-mapping)
                   :granule-ur.lowercase  string-field-mapping
                   :producer-gran-id (stored string-field-mapping)
                   :producer-gran-id.lowercase string-field-mapping
                   :day-night (stored string-field-mapping)
                   :day-night.lowercase string-field-mapping

                   ;; We need to sort by a combination of producer granule and granule ur
                   ;; It should use producer granule id if present otherwise the granule ur is used
                   ;; The producer granule id will be put in this field if present otherwise it
                   ;; will default to granule-ur. This avoids the solution Catalog REST uses which is
                   ;; to use a sort script which is (most likely) much slower.
                   :readable-granule-name-sort string-field-mapping

                   :platform-sn           string-field-mapping
                   :platform-sn.lowercase string-field-mapping
                   :instrument-sn         string-field-mapping
                   :instrument-sn.lowercase string-field-mapping
                   :sensor-sn             string-field-mapping
                   :sensor-sn.lowercase   string-field-mapping
                   :start-date (stored date-field-mapping)
                   :end-date (stored date-field-mapping)
                   :size (stored double-field-mapping)
                   :cloud-cover (stored double-field-mapping)
                   :orbit-calculated-spatial-domains orbit-calculated-spatial-domain-mapping
                   :project-refs string-field-mapping
                   :project-refs.lowercase string-field-mapping
                   :revision-date         date-field-mapping
                   :downloadable (stored bool-field-mapping)
                   :browsable (stored bool-field-mapping)
                   :attributes attributes-field-mapping

                   ;; atom-links is a json string that contains the atom-links, which is a list of
                   ;; maps of atom link attributes. We tried to use nested document to save atom-links
                   ;; as a structure in elasticsearch, but can't find a way to retrieve it out.
                   ;; So we are saving the links in json string, then parse it out when we need it.
                   :atom-links (not-indexed (stored string-field-mapping))
                   }
                  spatial-coverage-fields)}})

(defn index-set
  "Returns the index-set configuration"
  [context]
  (let [colls-w-separate-indexes ((get-in context [:system :colls-with-separate-indexes-fn]))
        granule-indices (remove empty? (concat colls-w-separate-indexes ["small_collections"]))]
    {:index-set {:name "cmr-base-index-set"
                 :id 1
                 :create-reason "indexer app requires this index set"
                 :collection {:index-names ["collections"]
                              :settings collection-setting
                              :mapping collection-mapping}
                 :granule {:index-names granule-indices
                           :settings granule-setting
                           :mapping granule-mapping}}}))

(defn reset
  "Reset configured elastic indexes"
  [context]
  (let [index-set-root-url (transmit-conn/root-url
                             (transmit-config/context->app-connection context :index-set))
        index-set-reset-url (format "%s/reset" index-set-root-url)]
    (client/request
      {:method :post
       :url index-set-reset-url
       :content-type :json
       :accept :json})))

(defn create
  "Submit a request to create index-set"
  [context index-set]
  (let [index-set-root-url (transmit-conn/root-url
                             (transmit-config/context->app-connection context :index-set))
        index-set-url (format "%s/index-sets" index-set-root-url)
        response (client/request
                   {:method :post
                    :url index-set-url
                    :body (cheshire/generate-string index-set)
                    :content-type :json
                    :accept :json
                    :throw-exceptions false})
        status (:status response)]
    (when-not (= 201 status)
      (errors/internal-error! (format "Failed to create index-set: %s, errors: %s"
                                      (cheshire/generate-string index-set)
                                      (:body response))))))

(defn fetch-concept-type-index-names
  "Fetch index names for each concept type from index-set app"
  ([context]
   (let [index-set-id (get-in (index-set context) [:index-set :id])]
     (fetch-concept-type-index-names context index-set-id)))
  ([context index-set-id]
   (let [fetched-index-set (index-set/get-index-set context index-set-id)]
     (get-in fetched-index-set [:index-set :concepts]))))

(defn fetch-concept-mapping-types
  "Fetch mapping types for each concept type from index-set app"
  ([context]
   (let [index-set-id (get-in (index-set context) [:index-set :id])]
     (fetch-concept-mapping-types context index-set-id)))
  ([context index-set-id]
   (let [fetched-index-set (index-set/get-index-set context index-set-id)]
     {:collection (name (first (keys (get-in fetched-index-set [:index-set :collection :mapping]))))
      :granule (name (first (keys (get-in fetched-index-set [:index-set :granule :mapping]))))})))

(defn get-concept-type-index-names
  "Fetch index names associated with concepts."
  [context]
  (let [cache-atom (-> context :system :cache)]
    (cache/cache-lookup cache-atom :concept-indices (partial fetch-concept-type-index-names context))))

(defn get-concept-index-name
  "Return the concept index name for the given concept id"
  ([context concept-id revision-id]
   (let [concept-type (cs/concept-id->type concept-id)
         concept (when (= :granule concept-type) (meta-db/get-concept context concept-id revision-id))]
     (get-concept-index-name context concept-id revision-id concept)))
  ([context concept-id revision-id concept]
   (let [concept-type (cs/concept-id->type concept-id)
         indexes (get (get-concept-type-index-names context) concept-type)]
     (if (= :collection concept-type)
       (get indexes :collections)
       (let [coll-concept-id (:parent-collection-id (:extra-fields concept))]
         (get indexes (keyword coll-concept-id) (get indexes :small_collections)))))))

(defn get-index-name-for-granule-delete
  "Return the concept index name for granule delete based on the input collection concept id"
  [context coll-concept-id]
  (let [indexes (get (get-concept-type-index-names context) :granule)]
    (get indexes (keyword coll-concept-id) (get indexes :small_collections))))

(defn get-concept-mapping-types
  "Fetch mapping types associated with concepts."
  [context]
  (let [cache-atom (-> context :system :cache)]
    (cache/cache-lookup cache-atom :concept-mapping-types (partial fetch-concept-mapping-types context))))


