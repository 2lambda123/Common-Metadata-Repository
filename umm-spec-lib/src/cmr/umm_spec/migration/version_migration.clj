(ns cmr.umm-spec.migration.version-migration
  "Contains functions for migrating between versions of UMM schema."
  (:require [clojure.set :as set]
            [cmr.common.mime-types :as mt]
            [cmr.umm-spec.migration.organization-personnel-migration :as op]
            [cmr.umm-spec.migration.contact-information-migration :as ci]
            [cmr.umm-spec.versioning :refer [versions current-version]]
            [cmr.umm-spec.location-keywords :as lk]
            [cmr.umm-spec.util :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utility Functions

(defn- version-steps
  "Returns a sequence of version steps between begin and end, inclusive."
  [begin end]
  (->> (condp #(%1 %2) (compare begin end)
         neg?  (sort versions)
         zero? nil
         pos?  (reverse (sort versions)))
       (partition 2 1 nil)
       (drop-while #(not= (first %) begin))
       (take-while #(not= (first %) end))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Migrating Between Versions

;; Private Migration Functions

(defn- dispatch-migrate
  [_context _collection concept-type source-version dest-version]
  [concept-type source-version dest-version])

(defmulti ^:private migrate-umm-version
  "Returns the given data structure of the indicated concept type and UMM version updated to conform to the
  target UMM schema version."
  #'dispatch-migrate)

(defmethod migrate-umm-version :default
  [context c & _]
  ;; Do nothing by default. This lets us skip over "holes" in the version sequence, where the UMM
  ;; version may be updated but a particular concept type's schema may not be affected.
  c)

;; Collection Migrations

(def not-provided-organization
  "Place holder to use when an organization is not provided."
  {:Role "RESOURCEPROVIDER"
   :Party {:OrganizationName {:ShortName u/not-provided}}})

(def valid-iso-topic-categories
  "Valid values for ISOTopicCategory as defined in UMM JSON schema"
  #{"farming" "biota" "boundaries" "climatologyMeteorologyAtmosphere" "economy" "elevation"
    "environment" "geoscientificInformation" "health" "imageryBaseMapsEarthCover"
    "intelligenceMilitary" "inlandWaters" "location" "oceans" "planningCadastre" "society"
    "structure" "transportation" "utilitiesCommunication"})

(defn- get-iso-topic-category
  "Returns the UMM iso topic category or the default value of location if it is not a known
  iso topic category"
  [iso-topic-category]
  (get valid-iso-topic-categories iso-topic-category "location"))

(defmethod migrate-umm-version [:collection "1.0" "1.1"]
  [context c & _]
  (-> c
      (update-in [:TilingIdentificationSystem] #(when % [%]))
      (set/rename-keys {:TilingIdentificationSystem :TilingIdentificationSystems})))

(defmethod migrate-umm-version [:collection "1.1" "1.0"]
  [context c & _]
  (-> c
      (update-in [:TilingIdentificationSystems] first)
      (set/rename-keys {:TilingIdentificationSystems :TilingIdentificationSystem})))

(defmethod migrate-umm-version [:collection "1.1" "1.2"]
  [context c & _]
  ;; Change SpatialKeywords to LocationKeywords
  (-> c
      (assoc :LocationKeywords (lk/translate-spatial-keywords context (:SpatialKeywords c)))))

(defmethod migrate-umm-version [:collection "1.2" "1.1"]
  [context c & _]
  ;;Assume that IsoTopicCategories will not deviate from the 1.1 list of allowed values.
  (-> c
      (assoc :SpatialKeywords
             (or (seq (lk/location-keywords->spatial-keywords (:LocationKeywords c)))
                 (:SpatialKeywords c)
                 ;; Spatial keywords are required
                 [u/not-provided]))
      (assoc :LocationKeywords nil)))

(defmethod migrate-umm-version [:collection "1.2" "1.3"]
  [context c & _]
  (-> c
      (update-in [:PaleoTemporalCoverage] #(when % [%]))
      (set/rename-keys {:PaleoTemporalCoverage :PaleoTemporalCoverages})))

(defmethod migrate-umm-version [:collection "1.3" "1.2"]
  [context c & _]
  (-> c
      (update-in [:PaleoTemporalCoverages] first)
      (set/rename-keys {:PaleoTemporalCoverages :PaleoTemporalCoverage})))

(defmethod migrate-umm-version [:collection "1.3" "1.4"]
  [context c & _]
  (-> c
      (assoc :DataCenters (op/organizations->data-centers (:Organizations c)))
      (assoc :ContactPersons (op/personnel->contact-persons (:Personnel c)))
      (dissoc :Organizations :Personnel)))

(defmethod migrate-umm-version [:collection "1.4" "1.3"]
  [context c & _]
  (-> c
      (assoc :Organizations (op/data-centers->organizations (:DataCenters c)))
      (assoc :Personnel (op/contact-persons->personnel (:ContactPersons c)))
      (dissoc :DataCenters :ContactGroups :ContactPersons)))

(defn- update-attribute-description
  "If description is nil, set to default of 'Not provided'"
  [attribute]
  (if (nil? (:Description attribute))
     (assoc attribute :Description u/not-provided)
     attribute))

(defmethod migrate-umm-version [:collection "1.4" "1.5"]
  [context c & _]
  (-> c
    ;; If an Additional Attribute has no description, set the description
    ;; to the default "Not provided"
    (update-in [:AdditionalAttributes] #(mapv update-attribute-description %))))

(defmethod migrate-umm-version [:collection "1.5" "1.4"]
  [context c & _]
  ;; Don't need to migrate Additional Attribute description back since 'Not provided' is valid
  c)

(defmethod migrate-umm-version [:collection "1.5" "1.6"]
  [context c & _]
  (-> c
    (update-in [:DataCenters] #(mapv ci/update-data-center-contact-info %))
    (update-in [:ContactPersons] #(mapv ci/first-contact-info %))
    (update-in [:ContactGroups] #(mapv ci/first-contact-info %))))

(defmethod migrate-umm-version [:collection "1.6" "1.5"]
  [context c & _]
  (-> c
      (update-in [:DataCenters] #(mapv ci/update-data-center-contact-info-to-array %))
      (update-in [:ContactPersons] #(mapv ci/contact-info-to-array %))
      (update-in [:ContactGroups] #(mapv ci/contact-info-to-array %))))

(defmethod migrate-umm-version [:collection "1.6" "1.7"]
  [context c & _]
  (-> c
    (update :ISOTopicCategories #(mapv get-iso-topic-category %))))

(defmethod migrate-umm-version [:collection "1.7" "1.6"]
  [context c & _]
  ;; Don't need to migrate ISOTopicCategories
  c)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public Migration Interface

(defn migrate-umm
  [context concept-type source-version dest-version data]
  (if (= source-version dest-version)
    data
    ;; Migrating across versions is just reducing over the discrete steps between each version.
    (reduce (fn [data [v1 v2]]
              (migrate-umm-version context data concept-type v1 v2))
            data
            (version-steps source-version dest-version))))
