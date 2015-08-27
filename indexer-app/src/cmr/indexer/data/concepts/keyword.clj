(ns cmr.indexer.data.concepts.keyword
  "Contains functions to create keyword fields"
  (:require [clojure.string :as str]
            [cmr.common.concepts :as concepts]
            [cmr.indexer.data.concepts.science-keyword :as sk]
            [cmr.indexer.data.concepts.attribute :as attrib]
            [cmr.indexer.data.concepts.organization :as org]))

;; NOTE -  The following fields are marked as deprecated in the UMM documenation
;; and are therefore not used for keyword searches in the CMR:
;;    SuggestedUsage (ECHO10)
;;

;; Regex to split strings with special characters into multiple words for keyword searches
(def keywords-separator-regex #"[!@#$%^&()\-=_+{}\[\]|;'.,\\\"/:<>?`~* ]")

;; Aliases for NEAR_REAL_TIME
(def nrt-aliases
  ["near_real_time","nrt","near real time","near-real time","near-real-time","near real-time"])

(defn prepare-keyword-field
  [field-value]
  "Convert a string to lowercase then separate it into keywords"
  (when field-value
    (let [field-value (str/lower-case field-value)]
      (into [field-value] (str/split field-value keywords-separator-regex)))))

(defn create-keywords-field
  [concept-id collection other-fields]
  "Create a keyword field for keyword searches by concatenating several other fields
  into a single string"
  (let [{{:keys [short-name long-name version-id version-description
                 processing-level-id collection-data-type]} :product
         :keys [entry-id entry-title summary spatial-keywords temporal-keywords associated-difs
                projects]} collection
        {:keys [platform-long-names]} other-fields
        provider-id (:provider-id (concepts/parse-concept-id concept-id))
        collection-data-type (if (= "NEAR_REAL_TIME" collection-data-type)
                               nrt-aliases
                               collection-data-type)
        project-long-names (map :long-name projects)
        project-short-names (map :short-name projects)
        platforms (:platforms collection)
        platform-short-names (map :short-name platforms)
        instruments (mapcat :instruments platforms)
        instrument-short-names (keep :short-name instruments)
        instrument-long-names (keep :long-name instruments)
        instrument-techiques (keep :technique instruments)
        sensors (mapcat :sensors instruments)
        sensor-short-names (keep :short-name sensors)
        sensor-long-names (keep :long-name sensors)
        sensor-techniques (keep :technique sensors)
        characteristics (mapcat :characteristics platforms)
        char-names (keep :name characteristics)
        char-descs (keep :description characteristics)
        two-d-coord-names (map :name (:two-d-coordinate-systems collection))
        archive-centers (org/extract-archive-centers collection)
        science-keywords (sk/science-keywords->keywords collection)
        attrib-keywords (attrib/psas->keywords collection)
        all-fields (flatten (conj [concept-id]
                                  provider-id
                                  entry-title
                                  collection-data-type
                                  short-name
                                  long-name
                                  two-d-coord-names
                                  summary
                                  version-id
                                  version-description
                                  processing-level-id
                                  archive-centers
                                  science-keywords
                                  attrib-keywords
                                  spatial-keywords
                                  temporal-keywords
                                  associated-difs
                                  project-long-names
                                  project-short-names
                                  platform-short-names
                                  platform-long-names
                                  instrument-short-names
                                  instrument-long-names
                                  instrument-techiques
                                  sensor-short-names
                                  sensor-long-names
                                  sensor-techniques
                                  char-names
                                  char-descs))
        split-fields (set (mapcat prepare-keyword-field all-fields))]

    (str/join " " split-fields)))
