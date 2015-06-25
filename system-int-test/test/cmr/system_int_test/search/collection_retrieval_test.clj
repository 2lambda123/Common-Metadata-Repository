(ns cmr.system-int-test.search.collection-retrieval-test
  "Integration test for collection retrieval with cmr-concept-id"
  (:require [clojure.test :refer :all]
            [cmr.umm.echo10.collection :as c]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.mock-echo.client.echo-util :as e]
            [cmr.system-int-test.system :as s]
            [cmr.system-int-test.data2.core :as d]
            [cmr.system-int-test.utils.dev-system-util :as dev-sys-util]
            [cmr.common.mime-types :as mt]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"} false))

(comment
  (dev-sys-util/reset)
  (ingest/create-provider "provguid1" "PROV1")
  (ingest/create-provider "provguid2" "PROV2")

  (def user1-token (e/login (s/context) "user1"))

  (search/get-concept-by-concept-id "C1200000000-PROV1"
                                    {:query-params {:token user1-token}})

  (get-in user/system [:apps :search :caches :acls])

)

(deftest retrieve-collection-by-cmr-concept-id

  ;; Registered users have access to coll1
  (e/grant-registered-users (s/context) (e/coll-catalog-item-id "provguid1" ["coll1"]))

  ;; Ingest 2 early versions of coll1
  (d/ingest "PROV1" (dc/collection {:entry-title "coll1"
                                    :projects (dc/projects "ESI_1")}))
  (d/ingest "PROV1" (dc/collection {:entry-title "coll1"
                                    :projects (dc/projects "ESI_2")}))

  (let [umm-coll (dc/collection {:entry-title "coll1"
                                 :projects (dc/projects "ESI_3")})
        coll1 (d/ingest "PROV1" umm-coll)
        coll2 (d/ingest "PROV1" (dc/collection {:entry-title "Dataset2"}))
        del-coll (d/ingest "PROV1" (dc/collection))
        ;; tokens
        guest-token (e/login-guest (s/context))
        user1-token (e/login (s/context) "user1")]
    (ingest/delete-concept (d/item->concept del-coll :echo10))
    (index/wait-until-indexed)

    (testing "retrieval of a deleted collection results in a 404"
      (let [response (search/get-concept-by-concept-id (:concept-id del-coll)
                                                       {:query-params {:token user1-token}})]
        (is (= 404 (:status response)))
        (is (re-find #"Concept with concept-id: .*? could not be found" (:body response)))))
    (testing "retrieval by collection cmr-concept-id returns the latest revision."
      (let [response (search/get-concept-by-concept-id (:concept-id coll1)
                                                       {:query-params {:token user1-token}})
            parsed-collection (c/parse-collection (:body response))]
        (is (search/mime-type-matches-response? response mt/echo10))
        (is (= umm-coll parsed-collection))))
    (testing "retrieval with .xml extension returns correct mime type"
      (let [response (search/get-concept-by-concept-id (:concept-id coll1)
                                                       {:query-params {:token user1-token}
                                                        :url-extension "xml"})
            parsed-collection (c/parse-collection (:body response))]
        (is (search/mime-type-matches-response? response mt/echo10))
        (is (= umm-coll parsed-collection))))
    (testing "ACL enforced on retrieval"
      (let [response (search/get-concept-by-concept-id (:concept-id coll1) {:query-params {:token guest-token}})]
        (is (= 404 (:status response)))
        (is (re-find #"Concept with concept-id: C[0-9]+-PROV1 could not be found" (:body response)))))
    (testing "retrieval by collection cmr-concept-id, not found."
      (let [response (search/get-concept-by-concept-id "C1111-PROV1")]
        (is (= 404 (:status response)))
        (is (re-find #"Concept with concept-id: C1111-PROV1 could not be found" (:body response)))))))

