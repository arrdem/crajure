(ns crajure.util
  (:require [net.cgrand.enlive-html :as html]
            [rate-gate.core :refer [rate-limit]]
            [pandect.algo.sha256 :as sha]
            [clojure.java.io :as io]))

(defonce
  ^{:arglists '([url & properties])}
  fetch-url*
  (rate-limit
   (fn [url & properties]
     (-> (java.net.URL. url)
         .openConnection
         (doto (.setRequestProperty "User-Agent" "Mozilla/5.0"))
         .getContent))
   1 1000))

(defn url->f [url]
  (.mkdirs (io/file "cache"))
  (io/file (str "cache/" (sha/sha256 url) ".html")))

(defn cache-fetch-url [url]
  (let [f (url->f url)]
    (when (.exists f)
      (io/input-stream f))))

(defn cache-put-url [url data]
  (spit (url->f url) data))

(defn fetch-url [url & properties]
  (or (when-not (.contains url "?")
        (some-> (cache-fetch-url url)
                (html/html-resource)))
      (let [res (apply fetch-url* url properties)]
        (if-not (.contains url "?")
          (do (let [sw (java.io.StringWriter.)]
                (io/copy res sw)
                (cache-put-url url (.toString sw)))
              (fetch-url url))
          (html/html-resource res)))))

(defn has-page? [url]
  (boolean
   (fetch-url url)))

(defn dollar-str->int [x-dollars]
  (try (->> x-dollars
            rest
            (apply str)
            read-string)
       (catch Exception e
         (str "dollar-str->int got: "
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
