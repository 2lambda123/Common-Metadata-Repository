(ns cmr.search.services.parameters.conversion
  "Contains functions for parsing and converting query parameters to query conditions"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cmr.common.services.errors :as errors]
            [cmr.common-app.services.search.query-model :as cqm]
            [cmr.search.models.query :as qm]
            [cmr.common-app.services.search.group-query-conditions :as gc]
            [cmr.common.util :as u]
            [cmr.common-app.services.search.params :as common-params]
            [cmr.search.services.parameters.legacy-parameters :as lp]
            [cmr.search.services.tagging.tag-related-item-condition :as tag-related]
            [cmr.common.concepts :as cc]
            [cmr.common.date-time-parser :as parser]))

(defmethod common-params/param-mappings :collection
  [_]
  {:entry-title :string
   :entry-id :string
   :native-id :string
   :provider :string
   :attribute :attribute
   :short-name :string
   :version :string
   :updated-since :updated-since
   :revision-date :revision-date
   :processing-level-id :string
   :collection-data-type :collection-data-type
   :temporal :temporal
   :concept-id :string
   :platform :string
   :instrument :string
   :sensor :string
   :project :string
   :data-center :string
   :archive-center :string
   :spatial-keyword :string
   :two-d-coordinate-system-name :string
   :two-d-coordinate-system :two-d-coordinate-system
   :science-keywords :science-keywords
   :dif-entry-id :dif-entry-id
   :downloadable :boolean
   :browsable :boolean
   :polygon :polygon
   :bounding-box :bounding-box
   :point :point
   :keyword :keyword
   :line :line
   :exclude :exclude

   ;; Tag parameters
   :tag-namespace :tag-query
   :tag-value :tag-query
   :tag-category :tag-query
   :tag-originator-id :tag-query})

(defmethod common-params/param-mappings :granule
  [_]
  {:granule-ur :string
   :concept-id :granule-concept-id
   :collection-concept-id :string
   :producer-granule-id :string
   :day-night :string
   :readable-granule-name :readable-granule-name
   :provider :collection-query
   :entry-title :collection-query
   :attribute :attribute
   :short-name :collection-query
   :orbit-number :orbit-number
   :equator-crossing-longitude :equator-crossing-longitude
   :equator-crossing-date :equator-crossing-date
   :version :collection-query
   :updated-since :updated-since
   :revision-date :revision-date
   :temporal :temporal
   :platform :inheritance
   :instrument :inheritance
   :sensor :inheritance
   :project :string
   :cloud-cover :num-range
   :exclude :exclude
   :downloadable :boolean
   :polygon :polygon
   :bounding-box :bounding-box
   :point :point
   :line :line
   :browsable :boolean
   :two-d-coordinate-system :two-d-coordinate-system})

(defmethod common-params/param-mappings :tag
  [_]
  {:namespace :string
   :value :string
   :category :string
   :originator-id :string})

(defmethod common-params/always-case-sensitive-fields :collection
  [_]
  #{:concept-id :collection-concept-id})

(defmethod common-params/parameter->condition :keyword
  [_ _ value _]
  (cqm/text-condition :keyword (str/lower-case value)))

(defmethod common-params/parameter->condition :tag-query
  [concept-type param value options]
  (let [rename-tag-param #(keyword (str/replace (name %) "tag-" ""))
        tag-param-name (rename-tag-param param)
        options (u/map-keys rename-tag-param options)]
    (tag-related/tag-related-item-query-condition
      (common-params/parameter->condition :tag tag-param-name value options))))

;; Special case handler for concept-id. Concept id can refer to a granule or collection.
;; If it's a granule query with a collection concept id then we convert the parameter to :collection-concept-id
(defmethod common-params/parameter->condition :granule-concept-id
  [concept-type param value options]
  (let [values (if (sequential? value) value [value])
        {granule-concept-ids :granule
         collection-concept-ids :collection} (group-by (comp :concept-type cc/parse-concept-id) values)
        collection-cond (when (seq collection-concept-ids)
                          (common-params/string-parameter->condition :collection-concept-id collection-concept-ids {}))
        granule-cond (when (seq granule-concept-ids)
                       (common-params/string-parameter->condition :concept-id granule-concept-ids options))]
    (if (and collection-cond granule-cond)
      (gc/and-conds [collection-cond granule-cond])
      (or collection-cond granule-cond))))

;; Construct an inheritance query condition for granules.
;; This will find granules which either have explicitly specified a value
;; or have not specified any value for the field and inherit it from their parent collection.
(defmethod common-params/parameter->condition :inheritance
  [concept-type param value options]
  (let [field-condition (common-params/parameter->condition :collection param value options)]
    (gc/or-conds
      [field-condition
       (gc/and-conds
         [(qm/->CollectionQueryCondition field-condition)
          (cqm/map->MissingCondition {:field param})])])))

(defmethod common-params/parameter->condition :updated-since
  [concept-type param value options]
  (cqm/map->DateRangeCondition
    {:field param
     :start-date (parser/parse-datetime
                   (if (sequential? value) (first value) value))
     :end-date nil}))

(defmethod common-params/parameter->condition :revision-date
  [concept-type param value options]
  (if (sequential? value)
    (if (= "true" (get-in options [:revision-date :and]))
      (gc/and-conds
        (map #(common-params/parameter->condition concept-type param % options) value))
      (gc/or-conds
        (map #(common-params/parameter->condition concept-type param % options) value)))
    (let [[start-date end-date] (map str/trim (str/split value #","))]
      (cqm/map->DateRangeCondition
        {:field param
         :start-date (when-not (str/blank? start-date) (parser/parse-datetime start-date))
         :end-date (when-not (str/blank? end-date) (parser/parse-datetime end-date))}))))

(defmethod common-params/parameter->condition :readable-granule-name
  [concept-type param value options]
  (if (sequential? value)
    (if (= "true" (get-in options [param :and]))
      (gc/and-conds
        (map #(common-params/parameter->condition concept-type param % options) value))
      (gc/or-conds
        (map #(common-params/parameter->condition concept-type param % options) value)))
    (let [case-sensitive (common-params/case-sensitive-field? :readable-granule-name options)
          pattern (pattern-field? :readable-granule-name options)]
      (gc/or-conds
        [(cqm/string-condition :granule-ur value case-sensitive pattern)
         (cqm/string-condition :producer-granule-id value case-sensitive pattern)]))))

(defn- collection-data-type-matches-science-quality?
  "Convert the collection-data-type parameter with wildcards to a regex. This function
  does not fully handle escaping special characters and is only intended for handling
  the special case of SCIENCE_QUALITY."
  [param case-sensitive]
  (let [param (if case-sensitive
                (str/upper-case param)
                param)
        pattern (-> param
                    (str/replace #"\?" ".")
                    (str/replace #"\*" ".*")
                    re-pattern)]
    (re-find pattern "SCIENCE_QUALITY")))

(defmethod common-params/parameter->condition :collection-data-type
  [concept-type param value options]
  (if (sequential? value)
    (if (= "true" (get-in options [param :and]))
      (gc/and-conds
        (map #(common-params/parameter->condition concept-type param % options) value))
      (gc/or-conds
        (map #(common-params/parameter->condition concept-type param % options) value)))
    (let [case-sensitive (common-params/case-sensitive-field? :collection-data-type options)
          pattern (pattern-field? :collection-data-type options)]
      (if (or (= "SCIENCE_QUALITY" value)
              (and (= "SCIENCE_QUALITY" (str/upper-case value))
                   (not= "false" (get-in options [:collection-data-type :ignore-case])))
              (and pattern
                   (collection-data-type-matches-science-quality? value case-sensitive)))
        ; SCIENCE_QUALITY collection-data-type should match concepts with SCIENCE_QUALITY
        ; or the ones missing collection-data-type field
        (gc/or-conds
          [(cqm/string-condition :collection-data-type value case-sensitive pattern)
           (cqm/map->MissingCondition {:field :collection-data-type})])
        (cqm/string-condition :collection-data-type value case-sensitive pattern)))))

;; dif-entry-id matches on entry-id or associated-difs
(defmethod common-params/parameter->condition :dif-entry-id
  [concept-type param value options]
  (gc/or-conds
    [(common-params/parameter->condition concept-type :entry-id value (set/rename-keys options {:dif-entry-id :entry-id}))
     (common-params/string-parameter->condition :associated-difs value (set/rename-keys options {:dif-entry-id :associated-difs}))]))

(defmethod common-params/parse-standard-params :collection
  [concept-type params]
  (let [{:keys [begin-tag end-tag snippet-length num-snippets]} (get-in params [:options :highlights])
        result-features (concat (when (= (:include-granule-counts params) "true")
                                  [:granule-counts])
                                (when (= (:include-has-granules params) "true")
                                  [:has-granules])
                                (when (= (:include-facets params) "true")
                                  (if (= "true" (:hierarchical-facets params))
                                    [:hierarchical-facets]
                                    [:facets]))
                                (when (= (:include-highlights params) "true")
                                  [:highlights])
                                (when-not (str/blank? (:include-tags params))
                                  [:tags]))]
    (-> (common-params/default-parse-standard-params :collection params lp/aliases)
        ;; If there is no sort key specified and keyword parameter exists then we default to
        ;; sorting by document relevance score
        (update :sort-key #(or % (when (:keyword params) [{:order :desc :field :score}])))
        (merge {:boosts (:boosts params)
                :result-features (seq result-features)
                :echo-compatible? (= "true" (:echo-compatible params))
                :all-revisions? (= "true" (:all-revisions params))
                :result-options (merge (when-not (str/blank? (:include-tags params))
                                         {:tags (map str/trim (str/split (:include-tags params) #","))})
                                       (when (or begin-tag end-tag snippet-length num-snippets)
                                         {:highlights
                                          {:begin-tag begin-tag
                                           :end-tag end-tag
                                           :snippet-length (when snippet-length (Integer. snippet-length))
                                           :num-snippets (when num-snippets (Integer. num-snippets))}}))}))))

(defmethod common-params/parse-standard-params :granule
  [concept-type params]
  (-> (common-params/default-parse-standard-params :granule params lp/aliases)
      (merge {:echo-compatible? (= "true" (:echo-compatible params))})))

(defn timeline-parameters->query
  "Converts parameters from a granule timeline request into a query."
  [params]
  (let [{:keys [interval start-date end-date]} params
        query (common-params/parse-parameter-query
                :granule
                (dissoc params :interval :start-date :end-date))]
    ;; Add timeline request fields to the query so that they can be used later
    ;; for processing the timeline results.
    (assoc query
           :interval (keyword interval)
           :start-date (parser/parse-datetime start-date)
           :end-date (parser/parse-datetime end-date)
           ;; Indicate the result feature of timeline so that we can preprocess
           ;; the query and add aggregations and make other changes.
           :result-features [:timeline]
           :result-format :timeline)))
