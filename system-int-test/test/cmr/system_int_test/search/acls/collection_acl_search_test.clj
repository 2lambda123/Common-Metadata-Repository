(ns cmr.system-int-test.search.acls.collection-acl-search-test
  "Tests searching for collections with ACLs in place"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [cmr.common.services.messages :as msg]
            [cmr.common.util :refer [are2] :as util]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]
            [cmr.system-int-test.data2.atom :as da]
            [cmr.system-int-test.data2.opendata :as od]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.system :as s]
            [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]))


(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"
                                           "provguid3" "PROV3" "provguid4" "PROV4"}
                                          {:grant-all-search? false}))

(comment
  (dev-sys-util/reset)
  (ingest/create-provider "provguid1" "PROV1")
  (ingest/create-provider "provguid2" "PROV2")
  (ingest/create-provider "provguid3" "PROV3")
  )

(deftest invalid-security-token-test
  (is (= {:errors ["Token ABC123 does not exist"], :status 401}
         (search/find-refs :collection {:token "ABC123"}))))

(deftest expired-security-token-test
  (is (= {:errors ["Token [expired-token] has expired."], :status 401}
         (search/find-refs :collection {:token "expired-token"}))))

(deftest collection-search-with-acls-test
  ;; Grant permissions before creating data
  ;; Grant guests permission to coll1
  (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll1" "notexist"])))
  ;; restriction flag acl grants matches coll4
  (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll4"] {:min-value 4 :max-value 6})))

  ;; Grant undefined access values in prov3
  (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid3" (e/coll-id nil {:include-undefined true})))

  ;; all collections in prov2 granted to guests
  (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid2"))
  ;; grant registered users permission to coll2 and coll4
  (e/grant-registered-users (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll2" "coll4"])))
  ;; grant specific group permission to coll3, coll6, and coll8
  (e/grant-group (s/context) "group-guid1" (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll3"])))
  (e/grant-group (s/context) "group-guid2" (e/coll-catalog-item-id "provguid2" (e/coll-id ["coll6" "coll8"])))
  (e/grant-group (s/context) "group-guid3"
                 (e/coll-catalog-item-id "provguid4"
                                         (e/coll-id ["coll11-entry-title" "coll12-entry-title"])))


  (let [coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))
        coll2 (d/ingest "PROV1" (dc/collection {:entry-title "coll2"}))
        coll3 (d/ingest "PROV1" (dc/collection {:entry-title "coll3"}))
        coll4 (d/ingest "PROV1" (dc/collection {:entry-title "coll4"
                                                :access-value 5}))
        ;; no permission granted on coll5
        coll5 (d/ingest "PROV1" (dc/collection {:entry-title "coll5"}))

        ;; PROV2
        coll6 (d/ingest "PROV2" (dc/collection {:entry-title "coll6"}))
        coll7 (d/ingest "PROV2" (dc/collection {:entry-title "coll7"}))
        ;; A dif collection
        coll8 (d/ingest "PROV2" (dc/collection-dif
                                  {:entry-title "coll8"
                                   :entry-id "S8"
                                   :short-name "S8"
                                   :version-id "V8"
                                   :long-name "coll8"})
                        {:format :dif})
        ;; added for atom results
        coll8 (assoc coll8 :original-format "DIF")

        ;; PROV3
        coll9 (d/ingest "PROV3" (dc/collection {:entry-title "coll9"}))
        coll10 (d/ingest "PROV3" (dc/collection {:entry-title "coll10"
                                                 :access-value 12}))
        ;; PROV4
        ;; group3 has permission to read this collection revision
        coll11-1 (d/ingest "PROV4" (dc/collection {:entry-title "coll11-entry-title"
                                                   :native-id "coll11"}))
        ;; tombstone
        coll11-2 (assoc (ingest/delete-concept (d/item->concept coll11-1))
                        :entry-title "coll11-entry-title"
                        :deleted true
                        :revision-id 2)
        ;; no permissions to read this revision since entry-title has changed
        coll11-3 (d/ingest "PROV4" (dc/collection {:entry-title "coll11"
                                                   :native-id "coll11"}))
        ;; group 3 has permission to read this collection revision
        coll12-1 (d/ingest "PROV4" (dc/collection {:entry-title "coll12-entry-title"
                                                   :native-id "coll12"}))
        ;; no permissions to read this collection since entry-title has changed
        coll12-2 (d/ingest "PROV4" (dc/collection {:entry-title "coll12"
                                                   :native-id "coll12"}))
        ;; no permision to see this tombstone since it has same entry-title as coll12-2
        coll12-3 (assoc (ingest/delete-concept (d/item->concept coll12-2))
                        :deleted true
                        :revision-id 2)

        all-colls [coll1 coll2 coll3 coll4 coll5 coll6 coll7 coll8 coll9 coll10]
        guest-permitted-collections [coll1 coll4 coll6 coll7 coll8 coll9]
        guest-token (e/login-guest (s/context))
        user1-token (e/login (s/context) "user1")
        user2-token (e/login (s/context) "user2" ["group-guid1"])
        user3-token (e/login (s/context) "user3" ["group-guid1" "group-guid2"])
        user4-token (e/login (s/context) "user4" ["group-guid3"])]

    (index/wait-until-indexed)

    (testing "parameter search acl enforcement"
      (are [token items]
           (d/refs-match? items (search/find-refs :collection (when token {:token token})))

           ;; not logged in should be guest
           nil guest-permitted-collections

           ;; login and use guest token
           guest-token guest-permitted-collections

           ;; test searching as a user
           user1-token [coll2 coll4]

           ;; Test searching with users in groups
           user2-token [coll2 coll4 coll3]
           user3-token [coll2 coll4 coll3 coll6 coll8]))
    (testing "token can be sent through a header"
      (is (d/refs-match? [coll2 coll4]
                         (search/find-refs :collection {} {:headers {"Echo-Token" user1-token}}))))
    (testing "aql search parameter enforcement"
      (is (d/refs-match? [coll2 coll4]
                         (search/find-refs-with-aql :collection [] {} {:headers {"Echo-Token" user1-token}}))))
    (testing "Direct transformer retrieval acl enforcement"
      (testing "registered user"
        (d/assert-metadata-results-match
          :echo10 [coll2 coll4]
          (search/find-metadata :collection :echo10 {:token user1-token
                                                     :concept-id (conj (map :concept-id all-colls)
                                                                       "C9999-PROV1")})))
      (testing "guest access"
        (d/assert-metadata-results-match
          :echo10 guest-permitted-collections
          (search/find-metadata :collection :echo10 {:token guest-token
                                                     :concept-id (map :concept-id all-colls)})))
      (testing "user in groups"
        (d/assert-metadata-results-match
          :echo10 [coll4 coll6 coll3 coll8 coll2]
          (search/find-metadata :collection :echo10 {:token user3-token
                                                     :concept-id (map :concept-id all-colls)}))))
    (testing "ATOM ACL enforcement"
      (testing "all items"
        (let [coll-atom (da/collections->expected-atom
                          guest-permitted-collections
                          (format "collections.atom?token=%s&page_size=100" guest-token))]
          (is (= coll-atom (:results (search/find-concepts-atom :collection {:token guest-token
                                                                             :page-size 100}))))))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              coll-atom (da/collections->expected-atom
                          guest-permitted-collections
                          (str "collections.atom?token=" guest-token
                               "&page_size=100&concept_id="
                               (str/join "&concept_id=" concept-ids)))]
          (is (= coll-atom (:results (search/find-concepts-atom :collection {:token guest-token
                                                                             :page-size 100
                                                                             :concept-id concept-ids})))))))
    (testing "JSON ACL enforcement"
      (testing "all items"
        (let [coll-json (da/collections->expected-atom
                          guest-permitted-collections
                          (format "collections.json?token=%s&page_size=100" guest-token))]
          (is (= coll-json (:results (search/find-concepts-json :collection {:token guest-token
                                                                             :page-size 100}))))))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              coll-json (da/collections->expected-atom
                          guest-permitted-collections
                          (str "collections.json?token=" guest-token
                               "&page_size=100&concept_id="
                               (str/join "&concept_id=" concept-ids)))]
          (is (= coll-json (:results (search/find-concepts-json :collection {:token guest-token
                                                                             :page-size 100
                                                                             :concept-id concept-ids})))))))

    (testing "opendata ACL enforcement"
      (testing "all items"
        (let [actual-od (search/find-concepts-opendata :collection {:token guest-token
                                                                    :page-size 100})]
          (od/assert-collection-opendata-results-match guest-permitted-collections actual-od)))

      (testing "by concept id"
        (let [concept-ids (map :concept-id all-colls)
              actual-od (search/find-concepts-opendata :collection {:token guest-token
                                                                    :page-size 100
                                                                    :concept-id concept-ids})]
          (od/assert-collection-opendata-results-match guest-permitted-collections actual-od))))

    (testing "all_revisions"
      (are2 [collections params]
            (d/refs-match? collections (search/find-refs :collection params))

            ;; only old revisions satisfy ACL - they should not be returned
            "provider-id all-revisions=false"
            []
            {:provider-id "PROV4" :all-revisions false :token user4-token}

            ;; only permissioned revisions are returned - including tombstones
            "provider-id all-revisions=true"
            [coll11-1 coll11-2 coll12-1]
            {:provider-id "PROV4" :all-revisions true :token user4-token}

            ;; none of the revisions are readable by guest users
            "provider-id all-revisions=true no token"
            []
            {:provider-id "PROV4" :all-revisions true}))))


;; This tests that when acls change after collections have been indexed that collections will be
;; reindexed when ingest detects the acl hash has change.
(deftest acl-change-test
  (let [acl1 (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll1"])))
        acl2 (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid2" (e/coll-id ["coll3"])))
        coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))
        coll2 (d/ingest "PROV1" (dc/collection {:entry-title "coll2"}))
        coll3 (d/ingest "PROV2" (dc/collection {:entry-title "coll3"}))
        coll4 (d/ingest "PROV2" (dc/collection {:entry-title "coll4"}))]

    (testing "normal reindex collection permitted groups"
      (index/wait-until-indexed)
      (ingest/reindex-collection-permitted-groups)
      (index/wait-until-indexed)

      ;; before acls change
      (is (d/refs-match? [coll1 coll3] (search/find-refs :collection {})))

      ;; Grant collection 2
      (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll2"])))
      ;; Ungrant collection 3
      (e/ungrant (s/context) acl2)

      ;; Try searching again before the reindexing
      (is (d/refs-match? [coll1 coll3] (search/find-refs :collection {})))

      ;; Reindex collection permitted groups
      (ingest/reindex-collection-permitted-groups)
      (index/wait-until-indexed)

      ;; Search after reindexing
      (is (d/refs-match? [coll1 coll2] (search/find-refs :collection {}))))

    (testing "reindex all collections"

      ;; Grant collection 4
      (e/grant-guest (s/context) (e/coll-catalog-item-id "provguid2" (e/coll-id ["coll4"])))

      ;; Try before reindexing
      (is (d/refs-match? [coll1 coll2] (search/find-refs :collection {})))

      ;; Reindex all collections
      ;; manually check the logs here. It should say it's reindexing provider 1 and provider 3 as well.
      (ingest/reindex-all-collections)
      (index/wait-until-indexed)

      ;; Search after reindexing
      (is (d/refs-match? [coll1 coll2 coll4] (search/find-refs :collection {}))))))

;; Verifies that tokens are cached by checking that a logged out token still works after it was used.
;; This isn't the desired behavior. It's just a side effect that shows it's working.
(deftest cache-token-test
  (let [acl1 (e/grant-registered-users (s/context) (e/coll-catalog-item-id "provguid1" (e/coll-id ["coll1"])))
        coll1 (d/ingest "PROV1" (dc/collection {:entry-title "coll1"}))
        user1-token (e/login (s/context) "user1")
        user2-token (e/login (s/context) "user2")]

    (index/wait-until-indexed)

    ;; A logged out token is normally not useful
    (e/logout (s/context) user2-token)
    (is (= {:errors ["Token ABC-2 does not exist"], :status 401}
           (search/find-refs :collection {:token user2-token})))

    ;; Use user1-token so it will be cached
    (is (d/refs-match? [coll1] (search/find-refs :collection {:token user1-token})))

    ;; logout
    (e/logout (s/context) user1-token)
    ;; The token should be cached
    (is (d/refs-match? [coll1] (search/find-refs :collection {:token user1-token})))))


