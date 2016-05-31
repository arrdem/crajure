(ns crajure.areas
  (:require [net.cgrand.enlive-html :as html]
            [crajure.util :as u]))

(defn generate-areas []
  (->> (html/select
        (u/fetch-url "https://www.craigslist.org/about/sites")
        [:li :> :a])
       (map (comp :href :attrs))
       (map #(-> (re-find #"\/\/([a-z]*)" %) second))
       set))

(def areas
  (memoize generate-areas))

(defn area-map []
  (assoc
   (into {} (map (fn [i] [(keyword i) i]) (areas)))
   :all (vec (areas))))

(defn as-areas [area-key]
  (if-let [code (get (area-map) (keyword area-key))]
    code
    (throw (Exception. (str "Invalid Area Code, " area-key)))))
