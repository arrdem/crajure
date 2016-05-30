(ns crajure.util
  (:require [clojure.java.io :as io]
            [net.cgrand.enlive-html :as html]
            [pandect.algo.sha256 :refer [sha256]]
            [rate-gate.core :refer [rate-limit]])
  (:import [java.io StringReader StringWriter]))

(defonce
  ^{:arglists '([url & properties])}
  fetch-url*
  (rate-limit
   (fn [url & properties]
     (let [sw (StringWriter.)]
       (with-open [in (-> (java.net.URL. url)
                          .openConnection
                          (doto (.setRequestProperty
                                 "User-Agent" "Mozilla/5.0"))
                          .getContent)]
         (io/copy in sw)
         (.toString sw))))
   2 1000))

(defn url->file [url]
  (let [cached (io/file "cache")]
    (.mkdirs cached)
    (io/file cached (str (sha256 url) ".html"))))

(defn fetch-cache [url]
  (slurp (url->file url)))

(defn put-cache [url content]
  (spit (url->file url) content))

(defn fetch-url [url]
  (html/html-resource
   (StringReader.
    (or (when-not (.contains url "?")
          (fetch-cache url))
        (let [result (fetch-url* url)]
          (when-not (.contains url "?")
            (put-cache url result))
          result)))))

(defn has-page? [url]
  (boolean
   (fetch-url url)))

(defn dollar-str->int [x-dollars]
  (try (->> x-dollars
            rest
            (apply str)
            read-string)
       (catch Exception e (str "dollar-str->int got: "
                               x-dollars ", not a dollar amount."))))

(defn round-to-nearest [to from]
  (let [add (/ to 2)
        divd (int (/ (+ add from) to))]
    (* divd to)))

(defn map-or-1
  "like map, but can also act on a single item
  (map inc 1)   ;=> [2]
  (map inc [1]) ;=> [2]"
  [f coll]
  (if (seq? coll)
    (map f coll)
    (map f [coll])))

(defn ->flat-seq
  [x]
  (flatten
   (if (seq? x)
     (vec x)
     (vector x))))
