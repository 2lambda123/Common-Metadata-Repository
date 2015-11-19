(ns cmr.umm.dif.collection
  "Contains functions for parsing and generating the DIF dialect."
  (:require [clojure.data.xml :as x]
            [clojure.java.io :as io]
            [cmr.common.util :as util]
            [cmr.common.xml :as cx]
            [cmr.umm.dif.core :as dif-core]
            [cmr.umm.collection :as c]
            [cmr.common.xml :as v]
            [camel-snake-kebab.core :as csk]
            [cmr.umm.dif.collection.project-element :as pj]
            [cmr.umm.dif.collection.related-url :as ru]
            [cmr.umm.dif.collection.science-keyword :as sk]
            [cmr.umm.dif.collection.org :as org]
            [cmr.umm.dif.collection.progress :as progress]
            [cmr.umm.dif.collection.temporal :as t]
            [cmr.umm.dif.collection.product-specific-attribute :as psa]
            [cmr.umm.dif.collection.collection-association :as ca]
            [cmr.umm.dif.collection.platform :as platform]
            [cmr.umm.dif.collection.spatial-coverage :as sc]
            [cmr.umm.dif.collection.extended-metadata :as em]
            [cmr.umm.dif.collection.personnel :as personnel])
  (:import cmr.umm.collection.UmmCollection))

(defn- xml-elem->Product
  "Returns a UMM Product from a parsed Collection Content XML structure"
  [collection-content]
  (let [short-name (cx/string-at-path collection-content [:Entry_ID])
        long-name (cx/string-at-path collection-content [:Entry_Title])
        long-name (util/trunc long-name 1024)
        version-id (or (cx/string-at-path collection-content [:Data_Set_Citation :Version])
                       dif-core/value-not-provided)
        processing-level-id (em/extended-metadata-value
                              collection-content em/product_level_id_external_meta_name)
        collection-data-type (em/extended-metadata-value
                               collection-content em/collection_data_type_external_meta_name)]
    (c/map->Product {:short-name short-name
                     :long-name long-name
                     :version-id version-id
                     :processing-level-id processing-level-id
                     :collection-data-type collection-data-type})))

(defn- xml-elem->DataProviderTimestamps
  "Returns a UMM DataProviderTimestamps from a parsed Collection Content XML structure"
  [collection-content]
  (let [insert-time (cx/string-at-path collection-content [:DIF_Creation_Date])
        update-time (cx/string-at-path collection-content [:Last_DIF_Revision_Date])]
    (when (or insert-time update-time)
      (c/map->DataProviderTimestamps
        {:insert-time (t/string->datetime insert-time)
         :update-time (t/string->datetime update-time)}))))

(def umm-dif-publication-reference-mappings
  "A seq of [umm-key dif-tag-name] which maps between the UMM
  PublicationReference fields and the DIF Reference XML element."
  (map (fn [x]
         (if (keyword? x)
           [(csk/->kebab-case-keyword x) x]
           x))
       [:Author
        :Publication_Date
        :Title
        :Series
        :Edition
        :Volume
        :Issue
        :Report_Number
        :Publication_Place
        :Publisher
        :Pages
        :ISBN
        :DOI
        [:related-url :Online_Resource]
        :Other_Reference_Details]))

(defn parse-publication-references
  "Returns a seq of publication references from a DIF collection XML node."
  [dif-xml-root]
  (for [reference (cx/elements-at-path dif-xml-root [:Reference])]
    (c/map->PublicationReference
     (into {} (for [[umm-key dif-tag] umm-dif-publication-reference-mappings]
                [umm-key (cx/string-at-path reference [dif-tag])])))))

(defn- xml-elem->Collection
  "Returns a UMM Product from a parsed Collection XML structure"
  [xml-struct]
  (c/map->UmmCollection
    {:entry-id (cx/string-at-path xml-struct [:Entry_ID])
     :entry-title (cx/string-at-path xml-struct [:Entry_Title])
     :summary (cx/string-at-path xml-struct [:Summary :Abstract])
     :purpose (cx/string-at-path xml-struct [:Summary :Purpose])
     :product (xml-elem->Product xml-struct)
     :quality (cx/string-at-path xml-struct [:Quality])
     :use-constraints (cx/string-at-path xml-struct [:Use_Constraints])
     :data-provider-timestamps (xml-elem->DataProviderTimestamps xml-struct)
     ;; See CMR-588
     ;:spatial-keywords (seq (cx/strings-at-path xml-struct [:Location]))
     :temporal-keywords (seq (cx/strings-at-path xml-struct [:Data_Resolution :Temporal_Resolution]))
     :temporal (t/xml-elem->Temporal xml-struct)
     :science-keywords (sk/xml-elem->ScienceKeywords xml-struct)
     :platforms (platform/xml-elem->Platforms xml-struct)
     :product-specific-attributes (psa/xml-elem->ProductSpecificAttributes xml-struct)
     :projects (pj/xml-elem->Projects xml-struct)
     :related-urls (ru/xml-elem->RelatedURLs xml-struct)
     :collection-associations (ca/xml-elem->CollectionAssociations xml-struct)
     :spatial-coverage (sc/xml-elem->SpatialCoverage xml-struct)
     :organizations (org/xml-elem->Organizations xml-struct)
     :personnel (personnel/xml-elem->personnel xml-struct)
     :publication-references (seq (parse-publication-references xml-struct))
     :collection-progress (progress/parse xml-struct)}))

(defn parse-collection
  "Parses DIF XML into a UMM Collection record."
  [xml]
  (xml-elem->Collection (x/parse-str xml)))

(defn parse-temporal
  "Parses the XML and extracts the temporal data."
  [xml]
  (t/xml-elem->Temporal (x/parse-str xml)))

(def dif-header-attributes
  "The set of attributes that go on the dif root element"
  {:xmlns "http://gcmd.gsfc.nasa.gov/Aboutus/xml/dif/"
   :xmlns:dif "http://gcmd.gsfc.nasa.gov/Aboutus/xml/dif/"
   :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
   :xsi:schemaLocation "http://gcmd.gsfc.nasa.gov/Aboutus/xml/dif/ http://gcmd.gsfc.nasa.gov/Aboutus/xml/dif/dif_v9.9.3.xsd"})

(defn generate-reference-elements
  "Returns a seq of DIF Reference elements from a seq of UMM publication references."
  [references]
  (for [reference references]
    (x/element :Reference {}
               (for [[umm-key dif-tag] umm-dif-publication-reference-mappings
                     :let [v (get reference umm-key)]
                     :when v]
                 (x/element dif-tag {} v)))))

(extend-protocol cmr.umm.dif.core/UmmToDifXml
  UmmCollection
  (umm->dif-xml
    ([collection]
     (let [{{:keys [version-id processing-level-id collection-data-type]} :product
            {:keys [insert-time update-time]} :data-provider-timestamps
            :keys [entry-id entry-title summary purpose temporal organizations science-keywords platforms
                   product-specific-attributes projects related-urls spatial-coverage
                   temporal-keywords personnel collection-associations quality use-constraints
                   publication-references]} collection
           ;; DIF only has range-date-times, so we ignore the temporal field if it is not of range-date-times
           temporal (when (seq (:range-date-times temporal)) temporal)]
       (x/emit-str
         (x/element :DIF dif-header-attributes
                    (x/element :Entry_ID {} entry-id)
                    (x/element :Entry_Title {} entry-title)
                    (when version-id
                      (x/element :Data_Set_Citation {}
                                 (x/element :Version {} version-id)))
                    (personnel/generate-personnel personnel)
                    (sk/generate-science-keywords science-keywords)
                    (platform/generate-platforms platforms)
                    (t/generate-temporal temporal)
                    (progress/generate collection)
                    (sc/generate-spatial-coverage spatial-coverage)
                    (for [tk temporal-keywords]
                      (x/element :Data_Resolution {} (x/element :Temporal_Resolution {} tk)))
                    (pj/generate-projects projects)
                    (x/element :Quality {} quality)
                    (x/element :Use_Constraints {} use-constraints)
                    (org/generate-data-center organizations)
                    (when publication-references
                      (generate-reference-elements publication-references))
                    (x/element :Summary {}
                               (x/element :Abstract {} summary)
                               (x/element :Purpose {} purpose))
                    (ru/generate-related-urls related-urls)
                    (ca/generate-collection-associations collection-associations)
                    (x/element :Metadata_Name {} "CEOS IDN DIF")
                    (x/element :Metadata_Version {} "VERSION 9.9.3")
                    (when insert-time
                      (x/element :DIF_Creation_Date {} (str insert-time)))
                    (when update-time
                      (x/element :Last_DIF_Revision_Date {} (str update-time)))

                    ;; There should be a single extended metadata element encompassing
                    ;; spatial-coverage, processing-level-id, collection-data-type, and additional
                    ;; attributes
                    (when (or spatial-coverage processing-level-id collection-data-type
                              product-specific-attributes)
                      (x/element :Extended_Metadata {}
                                 (psa/generate-product-specific-attributes
                                   product-specific-attributes)
                                 (sc/generate-spatial-coverage-extended-metadata spatial-coverage)
                                 (when processing-level-id
                                   (em/generate-metadata-elements
                                     [{:name em/product_level_id_external_meta_name
                                       :value processing-level-id}]))
                                 (when collection-data-type
                                   (em/generate-metadata-elements
                                     [{:name em/collection_data_type_external_meta_name
                                       :value collection-data-type}]))))))))))

(defn validate-xml
  "Validates the XML against the DIF schema."
  [xml]
  (v/validate-xml (io/resource "schema/dif/dif_v9.9.3.xsd") xml))

