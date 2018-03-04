(ns crajure.core
  "A tool kit for crawling Craigslist.

  Leverages Beetle for caching, rate limits, and makes some attempt to emulate a real browser."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"
             "Bryan Maass <bryan.maass@gmail.com>"],
   :license "Eclipse Public License 1.0"}
  (:require [beetle.core :as beetle]
            [clojure.tools.logging :as log]
            [crajure.areas :as a]
            [crajure.http :as http]
            [crajure.util :as u]
            [net.cgrand.enlive-html :as html]
            [clojure.string :as str]))

;; FIXME (arrdem 2018-03-04):
;;   This is woefully incomplete, and should really be factored out like areas is.
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

(defn make-req-map
  "Making a Craigslist search request"
  [{:keys [page area section query price/min price/max]
    :or   {page 0}}]
  (beetle/->req
   {:url          (format "http://%s.craigslist.org/search/%s" area (get section-map section section))
    :query-params {"s"         page
                   "query"     query
                   "sort"      "pricedsc"
                   "min_price" min
                   "max_price" max}}))

(defn item-count->page-count [num-selected-string]
  (-> num-selected-string read-string (/ 100) int inc))

(defn get-num-pages [{:keys [section area] :as query}]
  (try (let [url (make-req-map query)
             page (http/get url)
             num-selected (-> (html/select page [:.totalcount])
                              first :content first)]
         (item-count->page-count num-selected))
       (catch Exception e
         (log/warn e)
         0)))

(defn page->results [page]
  (html/select page [:li.result-row]))

(defn result->price [f]
  (->> (html/select f [:span.result-price])
       (map (comp u/dollar-str->int first :content))
       first))

(defn result->title [f]
  (->> (html/select f [:a.result-title])
       (map (comp first :content))
       first))

(defn result->date [f]
  (->> (html/select f [:time.result-date])
       (map (comp :datetime :attrs))
       first))

(defn result->item-url [f area]
  (->> (html/select f [:a.result-title])
       first :attrs :href))

(defn result->region [f]
  (->> (html/select f [:span.result-hood])
       first :content first))

(defn result->id [f]
  (-> f :attrs :data-pid))

(defn result->repost-of-id [f]
  (-> f :attrs :data-repost-of))

(defn result->image-ids [f]
  (if-let [ids (as-> (html/select f [:.result-image.gallery]) %
                 (first %) (:attrs %) (:data-ids %))]
    (as-> ids %
      (str/split % #",")
      (mapv #(str/replace % #".*?_" "") %)
      (set %))))

;; (defn page->preview [page]
;;   (->> (html/select page [:div.slide.first :img])
;;        first
;;        :attrs :src))

;; (defn page->address [page]
;;   (->> (html/select page [:.mapaddress])
;;        first
;;        :content
;;        (remove map?)
;;        (map str/trim)
;;        (remove empty?)
;;        (apply str)))

;; (defn page->reply [area page]
;;   (if-let [url (as-> page v
;;                  (html/select v [:a#replylink])
;;                  (first v)
;;                  (:attrs v)
;;                  (:href v))]
;;     (->> (html/select (http/get url) [:ul.pad :li :a])
;;          first :content (apply str) str/trim
;;          (str "mailto:"))))

;; (defn item-map->preview+address+reply [{:keys [url area] :as item}]
;;   (let [page (http/get url)]
;;     (assoc item
;;            :preview  (page->preview page)
;;            :address  (page->address page)
;;            :reply-to (page->reply area page))))

(defn result->item
  [area f]
  (let [url (result->item-url f area)]
    (try
      {:type      ::item
       :price     (result->price f)
       :title     (u/trim (result->title f))
       :date      (u/trim (result->date f))
       :region    (u/trim (result->region f))
       :area      area
       :item-url  (u/trim url)
       :id        (result->id f)
       :repost-of (result->repost-of-id f)
       :image-ids (result->image-ids f)
       ::html     f}
      (catch Exception e
        (throw (ex-info "Failure while loading result!"
                        {:area area
                         :f    f
                         :url  url}
                        e))))))

(defn req+area->items [req area]
  (let [page (http/get req)]
    (map (partial result->item area)
         (page->results page))))

(defn page-count->page-seq [page-count]
  (map #(-> % (* 100) str)
       (range 0 page-count)))

(defn mapcat*
  "A mapcat which doesn't force all the sub-iterators."
  [f [c & coll* :as coll]]
  (when coll
    (lazy-cat (f c)
              (mapcat* f coll*))))

(defn cl-item-seq [{:keys [area] :as query}]
  (let [page-count (get-num-pages query)
        page-range (page-count->page-seq page-count)]
    (mapcat* (fn [page-number]
               (let [req (make-req-map (assoc query :page page-number))]
                 (req+area->items req area)))
             page-range)))

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

;; FIXME (arrdem 2018-03-04):
;; - add :price/min
;; - add :price/max
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
  ([{:keys [query area section] :as q}]
   (let [section-seq (u/->flat-seq (get-section-code section))
         area-seq    (u/->flat-seq (get-area-code area))]
     (or (apply concat
                (for [a area-seq
                      s section-seq]
                  (-> (assoc q :area a :section s)
                      (cl-item-seq )
                      #_vector)))
         []))))
