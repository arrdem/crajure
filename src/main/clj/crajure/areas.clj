(ns crajure.areas
  (:require [net.cgrand.enlive-html :as html]
            [crajure.http :as http]))

(defn generate-areas []
  (->> (html/select
        (http/get "https://www.craigslist.org/about/sites")
        [:li :> :a])
       (map (comp :href :attrs))
       (keep #(-> (re-find #"\/\/([a-z]*)" %) second))
       set))

(def areas
  (memoize generate-areas))

(defn area-map []
  (assoc
   (into {} (map (fn [i] [(keyword i) i]) (areas)))
   :all (vec (areas))))
