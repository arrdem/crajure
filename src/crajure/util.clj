(ns crajure.util
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rate-gate.core :refer [rate-limit]]
            [net.cgrand.enlive-html :as html]
            [pandect.algo.sha256 :refer [sha256]])
  (:import [java.io File StringReader StringWriter]
           [java.net URLEncoder InetSocketAddress Proxy Proxy$Type URL URLConnection]))

(defn url-encode
  [string]
  (some-> string str
          (URLEncoder/encode "UTF-8")
          (.replace "+" "%20")))

(defn format-params [params]
  (->> (for [[k v] params]
         (str k "="
              (if-not (= k "query")
                (url-encode v) v)))
       (str/join "&")))

(def ^:dynamic *proxies*
  "If bound, an atom of the form

  {:candidates (set proxy-str)
   :usable     (set proxy-str)
   :unusable   (set proxy-str)}"
  nil)

(defn choose-proxy [struct]
  (let [{:keys [candidates usable]} struct]
    (if candidates
      (rand-nth (vec candidates))
      (rand-nth (vec usable)))))

(defn mark-proxy-successful [state proxy]
  (-> state
      (update :candidates disj proxy)
      (update :usable (fnil conj #{}) proxy)))

(defn mark-proxy-failed [state proxy]
  (-> state
      (update :candidates disj proxy)
      (update :unusable (fnil conj #{}) proxy)))

(defn make-proxy ^Proxy [proxy-str]
  (if proxy-str
    (let [[_ host port] (re-find #"([\d\.]*):(\d+)" proxy-str)]
      (Proxy.
       Proxy$Type/HTTP
       (InetSocketAddress.
        ^String host
        ^int (Integer/parseInt port))))
    Proxy/NO_PROXY))

(defn try-fetch [url]
  (let [l  (some->> *proxies* deref choose-proxy)
        p  (make-proxy l)
        sw (StringWriter.)]
    (printf "[try-fetch] %-30s %s\n" l url)
    (try
      (loop [url   url
             limit 5]
        (if-not (zero? limit)
          (let [conn (as-> (java.net.URL. ^String url) v
                       ^URLConnection (.openConnection v p)
                       (doto v
                         (.setRequestProperty "User-Agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.81 Safari/537.36")
                         (.setConnectTimeout (* 5 1000))
                         (.setRequestProperty "Content-Type" "text/html")
                         (.setRequestMethod "GET")))]
            (if (= 301 (.getResponseCode conn))
              (recur (.getHeaderField conn "Location") (dec limit))
              (do (io/copy ^Reader (.getInputStream conn) ^Writer sw)
                  (.toString sw))))
          (throw (Exception. "Ran out of redirects!"))))

      (catch java.net.SocketTimeoutException e
        ;; the proxy if any was bad
        (when l (swap! *proxies* mark-proxy-failed l))
        nil)
      
      (catch java.io.IOException e
        ;; case of 403
        (when l (swap! *proxies* mark-proxy-failed l))
        nil)
      
      (catch Exception e
        (println e)
        nil))))

(def fetch-url*
  (rate-limit
   (fn [url]
     (println "[fetch-url*]" url)
     (loop [i 100]
       (if-not (zero? i)
         (let [res (try-fetch url)]
           (if res
             (if (or (.contains res "/wrproxy/authenticate")
                     (.contains res "Data Limit Reached"))
               (do (println "[fetch-url*] Failproxy")
                   (recur (dec i)))
               res)
             (recur (dec i)))))))
   1 (* 5 1000)))

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

(defonce fetch-url
  (memoize
   (fn [url]
     (html/html-resource
      (StringReader.
       (or (when-not (.contains ^String url "?")
             (fetch-cache url))
           (let [result (fetch-url* url)]
             (when-not (.contains ^String url "?")
               (put-cache url result))
             result)))))))

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
