(ns crajure.core
  (:require [clojure.string :as str]
            [net.cgrand.enlive-html :as html]
            [crajure.util :as u]
            [crajure.areas :as a]))

(defn page+area+section+query->url [page-str area-str section-str query-str]
  (apply str
         (replace {:area    area-str
                   :section section-str
                   :page    page-str
                   :query   query-str}
                  ["http://" :area ".craigslist.org/search/" :section "?s="
                   :page "&query=" :query "&sort=pricedsc"])))

(defn area+section+query->url [area-str section-str query-str]
  (apply str
         (replace {:area    area-str
                   :section section-str
                   :page    "000"
                   :query   query-str}
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
                              (#(format "http://%s.craigslist.org/%s" area %)))]
    (->> (html/select (u/fetch-url reply-url) [:ul.pad :li :a])
         first :content (apply str) str/trim
         (str "mailto:"))))

(defn item-map->preview+address+reply [{:keys [url area] :as item}]
  (let [page (u/fetch-url url)]
    (assoc item
           :preview  (page->preview page)
           :address  (page->address page)
           :reply-to (page->reply area page))))

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

(defn cl-item-seq [area section query-str]
  (let [page-count (get-num-pages query-str section area)
        page-range (page-count->page-seq page-count)]
    (mapcat (fn [page-number]
              (let [url (page+area+section+query->url page-number area section query-str)]
                (url+area->items url area)))
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
