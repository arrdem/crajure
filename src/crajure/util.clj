(ns crajure.util
  (:require [clojure.java.io :as io]
            [net.cgrand.enlive-html :as html]
            [pandect.algo.sha256 :refer [sha256]]
            [clojure.string :as str])
  (:import [java.io File StringReader StringWriter]
           [java.net InetSocketAddress Proxy Proxy$Type URL URLConnection]))

(defn url->url+params [url]
  (let [[_ url bits] (re-find #"([^&\?]*)(\?.*)" url)
        kvs          (->> (str/split bits #"[\?&]")
                          (remove empty?)
                          (map #(vec (str/split % #"=")))
                          (into {}))]
    [url kvs]))

(def ^:dynamic *proxies*
  nil)

(defn make-proxy ^Proxy [proxy-str]
  (let [[_ host port] (re-find #"([\d\.]*):(\d+)" proxy-str)]
    (Proxy.
     Proxy$Type/HTTP
     (InetSocketAddress.
      ^String host
      ^int (Integer/parseInt port)))))

(defn open-with-proxy ^URLConnection [^URL u]
  (let [^Proxy p (if (and (bound? #'*proxies*)
                          (not (empty? *proxies*)))
                   (let [l (rand-nth *proxies*)
                         p (make-proxy l)]
                     (println "[open-with-proxy] Using proxy:" l)
                     p)
                   Proxy/NO_PROXY)]
    (.openConnection u p)))

(defn try-fetch [url]
  (try
    (let [sw (StringWriter.)]
      (with-open [in (as-> (java.net.URL. ^String url) v
                       ^URLConnection (open-with-proxy v)
                       (doto v
                         (.setRequestProperty "User-Agent" "Mozilla/5.0")
                         (.setConnectTimeout (* 15 1000))
                         (.setRequestMethod "GET"))
                       (.getContent v))]
        (io/copy ^Reader in ^Writer sw)
        (.toString sw)))
    (catch java.io.FileNotFoundException e nil)
    (catch java.io.IOException e nil)
    (catch Exception e
      (println e)
      nil)))

(defn fetch-url* [url]
  (println "[fetch-url*]" url)
  (loop [i 100]
    (if-not (zero? i)
      (let [res (try-fetch url)]
        (if res res (recur (dec i)))))))

(defn url->file [url]
  (let [cached (io/file "cache")]
    (.mkdirs cached)
    (io/file cached (str (sha256 url) ".html"))))

(defn fetch-cache [url]
  (let [^File f (url->file url)]
    (when (.exists f)
      (slurp f))))

(defn put-cache [url content]
  (spit (url->file url) content))

(defn fetch-url [url]
  (html/html-resource
   (StringReader.
    (or (when-not (.contains ^String url "?")
          (fetch-cache url))
        (let [result (fetch-url* url)]
          (when-not (.contains ^String url "?")
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
  (let [add  (/ to 2)
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
