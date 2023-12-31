(ns cmr.metadata-db.api.provider
  "Defines the HTTP URL routes for the application."
  (:require
   [cheshire.core :as json]
   [clojure.walk :as walk]
   [cmr.acl.core :as acl]
   [cmr.common.log :refer [debug info warn error]]
   [cmr.common.services.errors :as errors]
   [cmr.metadata-db.api.route-helpers :as rh]
   [cmr.metadata-db.services.provider-service :as provider-service]
   [compojure.core :refer :all]))

(defn- save-provider
  "Save a provider."
  [context params provider]
  (let [saved-provider-id (provider-service/create-provider context provider)]
    {:status 201
     :body (json/generate-string saved-provider-id)
     :headers rh/json-header}))

(defn- update-provider
  "Update a provider"
  [context params provider]
  (provider-service/update-provider context provider)
  {:status 200
   :body (json/generate-string provider)
   :headers rh/json-header})

(defn- delete-provider
  "Delete a provider and all its concepts."
  [context params provider-id]
  (provider-service/delete-provider context provider-id)
  {:status 204})

(defn- get-provider
  "Read a provider"
  [context params provider-id]
  (let [provider (provider-service/get-provider-by-id context provider-id true)]
    {:status 200
     :body (json/generate-string provider)
     :headers rh/json-header}))

(defn- get-providers
  "Get a list of provider ids"
  [context params]
  (let [providers (provider-service/get-providers context params)]
    {:status 200
     :body (json/generate-string providers)
     :headers rh/json-header}))

(defn- validate-consortiums
  "Throws error if consortiums contain anything other than alphabet, numbers,
  underscores and spaces. Spaces are used as delimiters."
  [consortiums]
  (when (re-find #"[^A-Za-z0-9_ ]" consortiums)
    (errors/throw-service-error
      :invalid-data
      (format "Invalid consortiums [%s]. Valid consortiums can only contain alphanumeric, underscore and space characters." consortiums))))

(def provider-api-routes
  (context "/providers" []
    ;; create a new provider
    (POST "/" {:keys [request-context params headers body]}
      (acl/verify-ingest-management-permission request-context :update)
      (let [consortiums (get body "consortiums")]
        (when consortiums
          (validate-consortiums consortiums))
        (save-provider request-context params (walk/keywordize-keys body))))

    ;; read a provider
    (GET "/:provider-id" {{:keys [provider-id] :as params} :params
                          request-context :request-context
                          headers :headers}
      (acl/verify-ingest-management-permission request-context :read)
      (get-provider request-context params provider-id))

    ;; update a provider
    (PUT "/:provider-id" {{:keys [provider-id] :as params} :params
                          provider-body :body
                          request-context :request-context
                          headers :headers}
      (when-let [consortiums (get provider-body ":Consortiums")]
        (validate-consortiums consortiums))
      (let [provider-map (walk/keywordize-keys provider-body)]
        (acl/verify-ingest-management-permission request-context :update)
        (update-provider request-context params provider-map)))

    ;; delete a provider
    (DELETE "/:provider-id" {{:keys [provider-id] :as params} :params
                             request-context :request-context
                             headers :headers}
      (acl/verify-ingest-management-permission request-context :update)
      (delete-provider request-context params provider-id))

    ;; get a list of providers
    (GET "/" {:keys [request-context params]}
      (get-providers request-context params))))
