(ns crajure.core
  (:require [clojure.string :as str]
            [crajure
             [areas :as a]
             [categories :as c]
             [util :as u :refer [format-params]]]
            [net.cgrand.enlive-html :as html]))

(defn q->search-url [{:keys [area section params] :as q}]
  (format "http://%s.craigslist.org/search/%s?%s"
          area section (format-params params)))

(defn search-str->query-str [search-str]
  (str/replace search-str " " "%20"))

(defn item-count->page-count [num-selected-string]
  (-> num-selected-string read-string (/ 100) int inc))

(defn get-num-pages [{:keys [section area] :as q}]
  (try (let [url                (-> q
                                    (assoc-in [:params "s"] 0)
                                    (q->search-url))
             page               (u/fetch-url url)
             num-selected-large (-> (html/select page [:a.totalcount])
                                    first :content first)
             num-selected       (or num-selected-large
                                    (-> (html/select page [:span.button.pagenum])
                                        html/texts first (str/split #" ") last))]
         (item-count->page-count num-selected))
       (catch Exception e 0)))

(defn list->fragments [page]
  (html/select page [:p.result-info]))

(defn fragment->price [f]
  (->> (html/select f [:span.result-price])
       (map (comp u/dollar-str->int first :content))
       first))

(defn fragment->title [f]
  (->> (html/select f [:a.result-title])
       (map (comp first :content))
       first))

(defn fragment->date [f]
  (->> (html/select f [:time.result-date])
       (map (comp :datetime :attrs))
       first))

(defn fragment->item-url [f area]
  (->> (html/select f [:a.result-title])
       (map (comp :href :attrs))
       (map (fn [u] (str "http://" area
                         ".craigslist.org" u)))
       first))

(defn fragment->region [f]
  (->> (html/select f [:a.result-hood])
       (map (comp str/trim first :content))
       (map (fn [s] (apply str (drop-last (rest s)))))
       first))

(defn page->preview [page]
  (->> (html/select page [:div.slide.first :img])
       first
       :attrs :src))

(defn page->address [page]
  (->> (html/select page [:.mapaddress])
       first
       :content
       (remove map?)
       (map str/trim)
       (remove empty?)
       (apply str)))

(defn page->reply [area page]
  (if-let [reply-url (some->> (html/select page [:a#replylink])
                              first :attrs :href
                              (#(format "http://%s.craigslist.org%s" area %)))]
    (->> (html/select (u/fetch-url reply-url) [:ul.pad :li :a])
         first :content (apply str) str/trim
         (str "mailto:"))))

(defn item-map->preview+address [{:keys [url area] :as item}]
  (let [page (u/fetch-url url)]
    (assoc item
           :preview  (page->preview page)
           :address  (page->address page))))

(defn trim [o]
  (when o
    (str/trim o)))

(defn lower [o]
  (when o
    (str/lower-case o)))

(defn ->item-map [area price title date item-url region]
  {:price  price
   :title  (trim title)
   :date   (trim date)
   :region (lower (trim region))
   :area   area
   :url    (trim item-url)})

(defonce fragment->item
  (memoize
   (fn [area f]
     (let [url (fragment->item-url f area)]
       (->item-map
        area
        (fragment->price f)
        (fragment->title f)
        (fragment->date f)
        url
        (fragment->region f))))))

(defn url+area->items [url area]
  (let [page (u/fetch-url url)]
    (map (partial fragment->item area)
         (list->fragments page))))

(defn page-count->page-seq [page-count]
  (map #(-> % (* 100) str)
       (range 0 page-count)))

(defn cl-item-seq [{:keys [section area] :as q}]
  (let [page-count (get-num-pages q)
        page-range (page-count->page-seq page-count)]
    (mapcat (fn [page-number]
              (let [url (q->search-url (assoc-in q [:params "s"] page-number))]
                (url+area->items url area)))
            page-range)))

(defn query-cl
  "where query map contains a map of
  :query - a string like \"fixie bikes\"
  :area - a keyword like :sfbay that is an official cl area or :all for every area
  :section - a key representing a section of cl, i.e. :for-sale or :all for every section.
  "
  ([query area section]
   (query-cl {:query   query
              :area    area
              :section section
              :params  {}}))
  ([{:keys [query area section params] :as q}]
   (let [terms       (search-str->query-str query)
         section-seq (c/as-selectors section)
         area-seq    (a/as-areas area)]
     (or (for [a       area-seq
               s       section-seq
               cl-item (cl-item-seq
                        (-> (assoc q
                                   :area a
                                   :section (:path s))
                            (assoc-in [:params "query"] terms)
                            (update-in [:params "sort"] #(or % "pricedsc"))))]
           cl-item)
         []))))
