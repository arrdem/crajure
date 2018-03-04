(ns crajure.core-test
  (:require [clojure.test :refer :all]
            [crajure.core :refer :all]))

(defn unsearchable-string []
  (str (repeat 10 (java.util.UUID/randomUUID))))

(deftest cl-query-works
  (testing "all maps recieved from query-result are identical and listed."
    (let [query-result   (query-cl {:query   "fixie bike"
                                    :area    "sfbay"
                                    :section :for-sale/all})
          keys-in-result (->> query-result (mapcat keys) set)]
      (is (= #{:price :title :date :region :url :preview} keys-in-result))))
  (testing "no results returns empty seq"
    (let [empty-results (query-cl (unsearchable-string) "sfbay" :for-sale/all)]
      (is (empty? empty-results)))))
