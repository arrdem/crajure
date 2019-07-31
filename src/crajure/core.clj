(ns crajure.core
  (:require [clojure.string :as str]
            [net.cgrand.enlive-html :as html]
            [crajure.util :as u]
            [crajure.areas :as a]
            [crajure.categories :as c])
  (:import java.net.URLEncoder))

(defn url-encode
  [string]
  (some-> string str
          (URLEncoder/encode "UTF-8")
          (.replace "+" "%20")))

(defn format-params [params]
  (->>  params
        (mapcat (fn [[k v]]
                  (if (or (not (seq? v))
                          (string? v))
                    [(str k "="
                          (if-not (= k "query")
                            (url-encode v) v))]
                    (map #(str k "=" (url-encode %)) v))))
        (str/join "&")))

(defn q->search-url [{:keys [area section params] :as q}]
  (format "http://%s.craigslist.org/search/%s?%s"
          area section (format-params params)))

(defn search-str->query-str [search-str]
  (str/replace search-str " " "%20"))

(defn item-count->page-count [num-selected-string]
  (-> num-selected-string read-string (/ 100) int inc))

(defn get-num-pages [{:keys [section area] :as q}]
  (let [url                (q->search-url q)
        page               (u/fetch-url url)
        num-selected-large (-> (html/select page [:a.totalcount])
                               first :content first)
        num-selected       (or num-selected-large
                               (-> (html/select page [:span.button.pagenum])
                                   html/texts first (str/split #" ") last))]
    (item-count->page-count num-selected)))

(defn list->fragments [page]
  (html/select page [:p.row :span.txt]))

(defn fragment->price [f]
  (->> (html/select f [:span.txt :span.price])
       (map (comp u/dollar-str->int first :content))
       first))

(defn fragment->title [f]
  (->> (html/select f [:span.txt :span.pl :a :span])
       (map (comp first :content))
       first))

(defn fragment->date [f]
  (->> (html/select f [:span.txt :time])
       (map (comp :datetime :attrs))
       first))

(defn fragment->item-url [f area]
  (->> (html/select f [:span.txt :span.pl :a])
       (map (comp :href :attrs))
       (map (fn [u] (str "http://" area
                        ".craigslist.org" u)))
       first))

(defn fragment->region [f]
  (->> (html/select f [:span.txt :span.pnr :small])
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
           :type     ::item+
           :preview  (page->preview page)
           :address  (page->address page))))

(defn trim [o]
  (when o
    (str/trim o)))

(defn lower [o]
  (when o
    (str/lower-case o)))

(defn ->item-map [area price title date item-url region]
  {:type   ::item
   :price  price
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

(defn cl-item-page [{:keys [section area] :as q} page-number]
  (let [url (q->search-url (assoc-in q [:params "s"] page-number))]
    (url+area->items url area)))

(defn cl-item-seq [{:keys [section area] :as q}]
  (let [page-count (get-num-pages q)
        page-range (page-count->page-seq page-count)]
    (mapcat (partial cl-item-page q) page-range)))

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
     (or (apply concat
                (for [a area-seq
                      s section-seq]
                  (cl-item-seq
                   (-> (assoc q
                              :area a
                              :section (:path s))
                       (assoc-in [:params "query"] terms)
                       (update-in [:params "sort"] #(or % "pricedsc"))))))
         []))))
