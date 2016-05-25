(ns crajure.core
  (:require [clojure.string :as str]
            [net.cgrand.enlive-html :as html]
            [crajure.util :as u]
            [crajure.areas :as a]))

(defn page+area+section+query->url [page-str area-str section-str query-str]
  (apply str
         (replace {:area area-str
                   :section section-str
                   :page page-str
                   :query query-str}
                  ["http://" :area ".craigslist.org/search/" :section "?s="
                   :page "&query=" :query "&sort=pricedsc"])))

(defn area+section+query->url [area-str section-str query-str]
  (apply str
         (replace {:area area-str
                   :section section-str
                   :page "000"
                   :query query-str}
                  ["http://" :area ".craigslist.org/search/" :section "?s="
                   :page "&query=" :query "&sort=pricedsc"])))

(defn search-str->query-str [search-str]
  (str/replace search-str " " "%20"))

(defn item-count->page-count [num-selected-string]
  (-> num-selected-string read-string (/ 100) int inc))

(defn get-num-pages [query-str section area]
  (try (let [url                (-> (area+section+query->url area section query-str)
                                    (str/replace "s=__PAGE_NUM__&" ""))
             page               (u/fetch-url url)
             num-selected-large (-> (html/select page [:a.totalcount])
                                    first :content first)
             num-selected       (or num-selected-large
                                    (-> (html/select page [:span.button.pagenum])
                                        html/texts first (str/split #" ") last))]
         (item-count->page-count num-selected))
       (catch Exception e 0)))

(defn page->prices [page]
  (->> (html/select page [:span.price])
       (map (comp u/dollar-str->int first :content))))

(defn page->titles [page]
  (->> (html/select page [:span.pl :a :span])
       (map (comp first :content))))

(defn page->dates [page]
  (->> (html/select page [:time])
       (map (comp :datetime :attrs))))

(defn page->item-urls [page area]
  (->> (html/select page [:span.pl :a])
       (map (comp :href :attrs))
       (map (fn [u] (str "http://" area
                        ".craigslist.org" u)))))

(defn page->regions [page]
  (->> (html/select page [:span.pnr :small])
       (map (comp str/trim first :content))
       (map (fn [s] (apply str (drop-last (rest s)))))))

(defonce
  ^{:arglists '([url])}
  url->preview
  (memoize
   (fn [url]
     (let [page (u/fetch-url url)]
       (->> (html/select page [:div.slide.first :img])
            first
            :attrs :src)))))

(defn page->previews [page area]
  (map url->preview (page->item-urls page area)))

(defn ->item-map [area price title preview date item-url region]
  {:price   price
   :title   (.trim ^String title)
   :preview (when preview (.trim ^String preview))
   :date    (.trim ^String date)
   :region  (.trim ^String region)
   :url     (.trim ^String item-url)})

(defonce
  ^{:arglists '([url area])}
  url+area->item-map
  (memoize
   (fn [url area]
     (let [page (u/fetch-url url)]
       (map ->item-map
            (repeat area)
            (page->prices page)
            (page->titles page)
            (page->previews page area)
            (page->dates page)
            (page->item-urls page area)
            (page->regions page))))))

(defn page-count->page-seq [page-count]
  (map #(-> % (* 100) str)
       (range 0 page-count)))

(defn cl-item-seq [area section query-str]
  (let [page-count (get-num-pages query-str section area)
        page-range (page-count->page-seq page-count)]
    (mapcat (fn [page-number]
              (let [url (page+area+section+query->url page-number area section query-str)]
                (url+area->item-map url area)))
            page-range)))

(def section-map
  {:community/all      "ccc"
   :events/all         "eee"
   :for-sale/all       "sss"
   :gigs/all           "ggg"
   :housing/all        "hhh"
   :housing/apartments "apa"
   :housing/office     "off"
   :housing/sublets    "sub"
   :housing/rooms      "roo"
   :jobs/all           "jjj"
   :personals/all      "ppp"
   :resumes/all        "rrr"
   :services/all       "bbb"
   :all                ["ppp" "ccc" "eee" "hhh" "sss" "rrr" "jjj" "ggg" "bbb"]})

(defn get-section-code
  [section-key]
  (if-let [code (get section-map section-key)]
    code
    (throw (Exception. (str "Invalid Section Code, " section-key)))))

(defn get-area-code
  [area-key]
  (if-let [code (get (a/area-map) (keyword area-key))]
    code
    (throw (Exception. (str "Invalid Area Code, " area-key)))))

(defn query-cl
  "where query map contains a map of
  :query - a string like \"fixie bikes\"
  :area - a keyword like :sfbay that is an official cl area or :all for every area
  :section - a key representing a section of cl, i.e. :for-sale or :all for every section.
  "
  ([query area section]
   (query-cl {:query   query
              :area    area
              :section section}))
  ([{:keys [query area section]}]
   (let [terms       (search-str->query-str query)
         section-seq (u/->flat-seq (get-section-code section))
         area-seq    (u/->flat-seq (get-area-code area))]
     (or (apply concat
                (for [a area-seq
                      s section-seq]
                  (cl-item-seq a s terms)))
         []))))
