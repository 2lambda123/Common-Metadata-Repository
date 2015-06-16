(ns cmr.metadata-db.int-test.concepts.search-test
  "Contains integration tests for getting concepts. Tests gets with various
  configurations including checking for proper error handling."
  (:require [clojure.test :refer :all]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            [clojure.data]
            [cmr.metadata-db.int-test.utility :as util]
            [cmr.metadata-db.services.messages :as msg]
            [clj-time.core :as t]
            [cmr.common.time-keeper :as tk]))

(use-fixtures :each (util/reset-database-fixture {:provider-id "REG_PROV" :small false}
                                                 {:provider-id "SMAL_PROV1" :small true}
                                                 {:provider-id "SMAL_PROV2" :small true}))

(defn concepts-for-comparison
  "Removes revision-date from concepts so they can be compared."
  [concepts]
  (map #(dissoc % :revision-date) concepts))

(deftest search-by-concept-revision-id-tuples
  (doseq [provider-id ["REG_PROV" "SMAL_PROV1"]]
    (let [coll1 (util/create-and-save-collection provider-id 1 3)
          coll2 (util/create-and-save-collection provider-id 2 3)
          coll3 (util/create-and-save-collection provider-id 3 3)
          gran1 (util/create-and-save-granule provider-id (:concept-id coll1) 1 2)
          gran2 (util/create-and-save-granule provider-id (:concept-id coll2) 2 2)]
      (are [item-revision-tuples]
           (let [tuples (map #(update-in % [0] :concept-id) item-revision-tuples)
                 {:keys [status concepts]} (util/get-concepts tuples)
                 expected-concepts (map (fn [[item revision]]
                                          (assoc item :revision-id revision))
                                        item-revision-tuples)]
             (and (= 200 status)
                  (= expected-concepts (concepts-for-comparison concepts))))
           ; one collection
           [[coll1 1]]
           ;; two collections
           [[coll1 2] [coll2 1]]
           ;; multiple versions of same collection
           [[coll1 2] [coll1 1]]
           ; granules and collections
           [[gran1 2] [gran1 1] [gran2 2] [coll3 3] [coll1 2]]))))

(deftest get-concepts-with-one-invalid-revision-id-test
  (doseq [provider-id ["REG_PROV" "SMAL_PROV1"]]
    (let [coll1 (util/create-and-save-collection provider-id 1)
          tuples [[(:concept-id coll1) 1]
                  [(:concept-id coll1) 2]
                  ["C2-REG_PROV" 1] ]
          {:keys [status errors]} (util/get-concepts tuples)]
      (is (= 404 status ))
      (is (= #{(msg/concept-with-concept-id-and-rev-id-does-not-exist (:concept-id coll1) 2)
               (msg/concept-with-concept-id-and-rev-id-does-not-exist "C2-REG_PROV" 1)}
             (set errors))))))

(deftest get-concepts-with-one-invalid-id-test-allow-missing
  (doseq [provider-id ["REG_PROV" "SMAL_PROV1"]]
    (let [coll1 (util/create-and-save-collection provider-id 1)
          tuples [[(:concept-id coll1) 1]
                  [(:concept-id coll1) 2]
                  ["C2-REG_PROV" 1] ]
          {:keys [status concepts]} (util/get-concepts tuples true)]
      (is (= 200 status ))
      (is (= [(:concept-id coll1)] (map :concept-id concepts))))))

(deftest get-latest-by-concept-id
  (let [coll1 (util/create-and-save-collection "REG_PROV" 1 3)
        coll2 (util/create-and-save-collection "REG_PROV" 2 1)
        coll3 (util/create-and-save-collection "SMAL_PROV1" 3 3)
        gran1 (util/create-and-save-granule "REG_PROV" (:concept-id coll1) 1 2)
        gran2 (util/create-and-save-granule "REG_PROV" (:concept-id coll2) 2 1)]
    (are [item-revision-tuples]
         (let [ids (map #(:concept-id (first %)) item-revision-tuples)
               {:keys [status concepts]} (util/get-latest-concepts ids)
               expected-concepts (map (fn [[item revision]]
                                        (assoc item :revision-id revision))
                                      item-revision-tuples)]
           (and (= 200 status)
                (= expected-concepts (concepts-for-comparison concepts))))
         ; one collection
         [[coll1 3]]
         ;; two collections
         [[coll1 3] [coll2 1]]
         ;; granules
         [[gran1 2] [gran2 1]]
         ; granules and collections
         [[gran1 2] [gran2 1] [coll3 3] [coll2 1]])))

(deftest get-latest-concepts-with-missing-concept-test
  (let [coll1 (util/create-and-save-collection "REG_PROV" 1)
        ids [(:concept-id coll1) "C1234-REG_PROV"]
        {:keys [status errors]} (util/get-latest-concepts ids)]
    (is (= 404 status ))
    (is (= #{(msg/concept-does-not-exist "C1234-REG_PROV")}
           (set errors)))))

(deftest get-latest-concepts-with-missing-concept-allow-missing-test
  (let [coll1 (util/create-and-save-collection "REG_PROV" 1)
        ids [(:concept-id coll1) "C1234-REG_PROV"]
        {:keys [status concepts]} (util/get-latest-concepts ids true)]
    (is (= 200 status ))
    (is (= [(:concept-id coll1)] (map :concept-id concepts)))))

(deftest find-collections
  (let [coll1 (util/create-and-save-collection "REG_PROV" 1)
        coll2 (util/create-and-save-collection "REG_PROV" 2)
        coll3 (util/create-and-save-collection "SMAL_PROV1" 3 1 {:extra-fields {:entry-id "entry-1"}})
        coll4 (util/create-and-save-collection "SMAL_PROV2" 4 1 {:extra-fields {:entry-id "entry-1"}})
        colls [coll1 coll2 coll3 coll4]
        [short1 short2 short3 short4] (map #(get-in % [:extra-fields :short-name]) colls)
        [vid1 vid2 vid3 vid4] (map #(get-in % [:extra-fields :version-id]) colls)
        [eid1 eid2 eid3 eid4] (map #(get-in % [:extra-fields :entry-id]) colls)
        [et1 et2 et3 et4] (map #(get-in % [:extra-fields :entry-title]) colls)]
    (testing "find by provider-id, short-name, version id"
      (testing "find one"
        (are [collection provider-id short-name version-id]
             (= [collection]
                (-> (util/find-concepts :collection {:provider-id provider-id
                                                     :short-name short-name
                                                     :version-id version-id})
                    :concepts
                    concepts-for-comparison))
             coll1 "REG_PROV" short1 vid1
             coll2 "REG_PROV" short2 vid2
             coll3 "SMAL_PROV1" short3 vid3
             coll4 "SMAL_PROV2" short4 vid4))
      (testing "find none"
        (are [provider-id sn vid] (= {:status 200 :concepts []}
                                     (util/find-concepts
                                       :collection
                                       {:provider-id provider-id :short-name sn :version-id vid}))
             "REG_PROV" "none" vid1
             "REG_PROV" short1 "none"
             "SMAL_PROV1" short1 vid1
             ;; Searching with an unknown provider id should just find nothing
             "PROVNONE" short1 vid1)))
    (testing "find by provider-id, entry-id"
      (testing "find one"
        (are [collection provider-id entry-id]
             (= [collection]
                (-> (util/find-concepts :collection {:provider-id provider-id
                                                     :entry-id entry-id})
                    :concepts
                    concepts-for-comparison))
             coll1 "REG_PROV" eid1
             coll2 "REG_PROV" eid2
             coll3 "SMAL_PROV1" "entry-1"
             coll4 "SMAL_PROV2" "entry-1"))
      (testing "find none"
        (are [provider-id eid] (= {:status 200 :concepts []}
                                  (util/find-concepts
                                    :collection
                                    {:provider-id provider-id :entry-id eid}))
             "REG_PROV" "none"
             "SMAL_PROV1" eid1
             ;; Searching with an unknown provider id should just find nothing
             "PROVNONE" eid1)))
    (testing "find by provider-id, entry-title"
      (testing "find one"
        (are [collection provider-id entry-title]
             (= [collection]
                (-> (util/find-concepts :collection {:provider-id provider-id
                                                     :entry-title entry-title})
                    :concepts
                    concepts-for-comparison))
             coll1 "REG_PROV" et1
             coll2 "REG_PROV" et2
             coll3 "SMAL_PROV1" et3
             coll4 "SMAL_PROV2" et4))
      (testing "find none"
        (are [provider-id et] (= {:status 200 :concepts []}
                                 (util/find-concepts
                                   :collection
                                   {:provider-id provider-id :entry-title et}))
             "REG_PROV" "none"
             "SMAL_PROV1" et1
             ;; Searching with an unknown provider id should just find nothing
             "PROVNONE" et1))))

  (let [coll5 (util/create-and-save-collection "REG_PROV" 4 3)
        eid5 (get-in coll5 [:extra-fields :entry-id])]
    (testing "find all revisions"
      (is (= 3
             (count (-> (util/find-concepts :collection {:provider-id "REG_PROV"
                                                         :entry-id eid5})
                        :concepts)))))
    (testing "find the latest revision"
      (is (= [coll5]
             (-> (util/find-latest-concepts :collection {:provider-id "REG_PROV"
                                                         :entry-id eid5})
                 :concepts
                 concepts-for-comparison))))))

(deftest get-expired-collections-concept-ids
  (let [time-now (tk/now)
        make-coll-expiring-in (fn [prov uniq-num num-revisions num-secs]
                                (let [expire-time (t/plus time-now (t/seconds num-secs))]
                                  (util/create-and-save-collection
                                    prov uniq-num num-revisions
                                    {:extra-fields {:delete-time (str expire-time)}})))
        ;; Expired a long time ago.
        coll1 (make-coll-expiring-in "REG_PROV" 1 1 -600000)
        coll2 (make-coll-expiring-in "REG_PROV" 2 2 -600000)
        ;; Expires in the far future
        coll3 (make-coll-expiring-in "REG_PROV" 3 1 500000)
        ;; Doesn't have an expiration date
        coll4 (util/create-and-save-collection "REG_PROV" 4 1)
        ;; Small providers
        coll5 (make-coll-expiring-in "SMAL_PROV1" 5 1 -600000)
        coll6 (util/create-and-save-collection "SMAL_PROV1" 6 1)
        coll7 (make-coll-expiring-in "SMAL_PROV2" 7 1 -600000)
        coll8 (util/create-and-save-collection "SMAL_PROV2" 8 1)]

    (testing "get expired collection concept ids"
      (are [provider-id collections]
      (let [{:keys [status concept-ids]} (util/get-expired-collection-concept-ids provider-id)]
        (= [200 (set (map :concept-id collections))]
               [status (set concept-ids)]))
      "REG_PROV" [coll1 coll2]
      "SMAL_PROV1" [coll5]
      "SMAL_PROV2" [coll7]))

    (testing "invalid or missing provider id"
      (is (= {:status 404, :errors ["Providers with provider-ids [PROVNONE] do not exist."]}
             (util/get-expired-collection-concept-ids "PROVNONE")))

      (is (= {:status 400, :errors ["A provider parameter was required but was not provided."]}
             (util/get-expired-collection-concept-ids nil))))))

(deftest find-collections-with-invalid-parameters
  (testing "missing parameters"
    (are [params] (= {:status 400
                      :errors [(msg/find-not-supported :collection params)]}
                     (util/find-concepts :collection (reduce #(assoc %1 %2 1) {} params)))
         [:provider-id :short-name]
         [:provider-id :version-id]
         [:short-name :version-id]
         []))
  (testing "extra parameters"
    (= {:status 400
        :errors [(msg/find-not-supported :collection [:provider-id :short-name :version-id :foo])]}
       (util/find-concepts :collection {:provider-id "REG_PROV"
                                        :short-name "f"
                                        :version-id "v"
                                        :foo "foo"}))))

(deftest find-granules-is-not-supported
  (= {:status 400
      :errors [(msg/find-not-supported :granule [])]}
     (util/find-concepts :granule {})))



