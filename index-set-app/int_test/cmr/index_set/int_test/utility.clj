(ns cmr.index-set.int-test.utility
  "Contains various utitiltiy methods to support integeration tests."
  (:require [clojure.test :refer :all]
            [clj-http.client :as client]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [cheshire.core :as cheshire]
            [clojurewerkz.elastisch.rest :as esr]
            [cmr.elastic-utils.config :as es-config]
            [cmr.transmit.config :as transmit-config]))

(defn index-set-root-url
  []
  (format "%s:%s"  "http://localhost" (transmit-config/index-set-port)))

;; url applicable to create, get and delete index-set
(defn index-set-url
  []
  (format "%s/%s" (index-set-root-url) "index-sets"))

(defn reset-url
  []
  (format "%s/%s" (index-set-root-url) "reset"))

(def cmr-concepts [:collection :granule])

;;; test data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def sample-index-set
  {:index-set
   {:name "cmr-base-index-set"
    :id 3
    :create-reason "include message about reasons for creating this index set"
    :collection {:indexes
                 [{:name "C4-collections"
                   :settings {:index {:number_of_shards 1,
                                      :number_of_replicas 0,
                                      :refresh_interval "20s"}}}
                  {:name "c6_Collections"
                   :settings {:index {:number_of_shards 1,
                                      :number_of_replicas 0,
                                      :refresh_interval "20s"}}}]
                 :mapping {:collection {:dynamic "strict",
                                        :_source {:enabled false},
                                        :_all {:enabled false},
                                        :_id   {:path "concept-id"},
                                        :properties {:concept-id  {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"},
                                                     :entry-title {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"}}}}}
    :granule {:indexes
              [{:name "G2-PROV1"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}
               {:name "G4-Prov3"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}
               {:name "g5_prov5"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}]
              :mapping {:granule {:dynamic "strict",
                                  :_source { "enabled" false},
                                  :_all {"enabled" false},
                                  :_id  {:path "concept-id"},
                                  :properties {:concept-id {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"},
                                               :collection-concept-id {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"}}}}}}})

(def invalid-sample-index-set
  {:index-set
   {:name "cmr-base-index-set"
    :id 3
    :create-reason "include message about reasons for creating this index set"
    :collection {:indexes
                 [{:name "C4-collections"}
                  {:name "c6_Collections"
                   :settings {:index {:number_of_shards 1,
                                      :number_of_replicas 0,
                                      :refresh_interval "20s"}}}]
                 :mapping {:collection {:dynamic "strict",
                                        :_source {:enabled false},
                                        :_all {:enabled false},
                                        :_id   {:path "concept-id"},
                                        :properties {:concept-id  {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"},
                                                     :entry-title {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"}}}}}
    :granule {:indexes
              [{:name "G2-PROV1"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}
               {:name "G4-Prov3"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}
               {:name "g5_prov5"
                :settings {:index {:number_of_shards 1,
                                   :number_of_replicas 0,
                                   :refresh_interval "10s"}}}]
              :mapping {:granule {:dynamic "strict",
                                  :_source { "enabled" false},
                                  :_all {"enabled" false},
                                  :_id  {:path "concept-id"},
                                  :properties {:concept-id {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"},
                                               :collection-concept-id {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"}}}}}}})

(def index-set-w-invalid-idx-prop
  {:index-set
   {:name "cmr-base-index-set"
    :id 3
    :create-reason "include message about reasons for creating this index set"
    :collection {:indexes
                 [{:name "C4-collections"
                   :settings {:index {:number_of_shards 1,
                                      :number_of_replicas 0,
                                      :refresh_interval "20s"}}}
                  {:name "c6_Collections"
                   :settings {:index {:number_of_shards 1,
                                      :number_of_replicas 0,
                                      :refresh_interval "20s"}}}]
                 :mapping {:collection {:dynamic "strict",
                                        :_source {:enabled false},
                                        :_all {:enabled false},
                                        :_id   {:path "concept-id"},
                                        :properties {:concept-id  {:type "XXX" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"},
                                                     :entry-title {:type "string" :index "not_analyzed" :omit_norms "true" :index_options "docs" :store "yes"}}}}}}})


;;; utility methods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn elastic-root
  []
  (format "http://%s:%s" (es-config/elastic-host) (es-config/elastic-port)))

(defn create-index-set
  "submit a request to index-set app to create indices"
  [idx-set]
  (let [response (client/request
                  {:method :post
                   :url (index-set-url)
                   :body (cheshire.core/generate-string idx-set)
                   :content-type :json
                   :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                   :accept :json
                   :throw-exceptions false})
        status (:status response)
        body (cheshire/parse-string (:body response))
        errors-str (cheshire/generate-string (flatten (get body "errors")))]
    {:status status :errors-str errors-str :response response}))

(defn delete-index-set
  "submit a request to index-set app to delete index-set"
  [id]
  (let [response (client/request
                  {:method :delete
                   :url (format "%s/%s" (index-set-url) id)
                   :accept :json
                   :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                   :throw-exceptions false})
        status (:status response)
        body (cheshire/parse-string (:body response))
        errors-str (cheshire/generate-string (flatten (get body "errors")))]
    {:status status :errors-str errors-str :response response}))

(defn get-index-set
  "submit a request to index-set app to fetch an index-set assoc with an id"
  [id]
  (let [response (client/request
                  {:method :get
                   :url (format "%s/%s" (index-set-url) id)
                   :accept :json
                   :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                   :throw-exceptions false})
        status (:status response)
        body (cheshire/parse-string (:body response))
        errors-str (cheshire/generate-string (flatten (get body "errors")))]
    {:status status :errors-str errors-str :response response}))

(defn get-index-sets
  "submit a request to index-set app to fetch all index-sets"
  []
  (let [response (client/request
                  {:method :get
                   :url (index-set-url)
                   :accept :json
                   :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                   :throw-exceptions false})
        status (:status response)
        body (cheshire/parse-string (:body response))
        errors-str (cheshire/generate-string (flatten (get body "errors")))]
    {:status status :errors-str errors-str :response response}))

(defn reset
  "test deletion of indices and index-sets"
  []
  (let [result (client/request
                {:method :post
                 :url (reset-url)
                 :headers {transmit-config/token-header (transmit-config/echo-system-token)}
                 :accept :json})
        status (:status result)
        {:keys [status errors-str response]} result]
    {:status status :errors-str errors-str :response response}))

(defn list-es-indices
  "List indices present in 'get index-sets' response"
  [index-sets]
  (apply concat
         (for [concept cmr-concepts idx-set index-sets]
           (vals (get-in idx-set [:concepts concept])))))


(def elastic-connection (atom nil))

(defn reset-fixture [f]
  (reset)
  (reset! elastic-connection (esr/connect (elastic-root)))
  (f)
  (reset))
