(ns crajure.http
  "Helpers for making HTTP request with faked user-agent strings,
  proxies, caches and other such awesomeness."
  (:refer-clojure :exclude [get])
  (:require [clojure.tools.logging :as log]
            [beetle.core :as beetle]
            [net.cgrand.enlive-html :as html]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pandect.algo.sha256 :as sha]
            [rate-gate.core :refer [rate-gate tarry]]
            [taoensso.nippy :as nippy])
  (:import java.io.File
           java.io.StringReader
           java.nio.charset.StandardCharsets
           [java.text DateFormat SimpleDateFormat]
           java.util.Date))

;; Rate limit middleware
;;--------------------------------------------------------------------------------------------------
(def ^{:dynamic true
       :doc "A DNS domain structure trie of rate gates."}
  *rate-limits*
  (atom {}))

(defn server-name->rate-limit [limits name]
  (let [paths (take-while not-empty (iterate butlast (reverse (str/split name #"\."))))]
    (first (keep #(get-in limits %) paths))))

(def ^{:dynamic true
       :doc "A 0-arity factory function for rate gates."}
  *default-rate-limit-fn*
  (fn []
    (rate-gate 1 1000)))

(defn get-or-create-rate-limit [limits server]
  (if (get-in limits server)
    limits
    (assoc-in limits server (*default-rate-limit-fn*))))

(defn wrap-rate-limiting-middleware
  "Function of a client which checks the server a request targets and
  rate gates requests on a per-service basis.

  Rate limits are maintained as shared state in `*rate-limits*`, and
  `*default-rate-limit-fn*` is used to hold a factory for new default
  rate limits.

  If `*rate-limits*` is `nil`, no limits are applied.

  If `*default-rate-limit-fn*` is `nil`, no rate limit will be applied
  to domains which do not already have a listed rate limit."
  [client]
  (fn [{:keys [server-name] :as req}]
    (let [gates (when *rate-limits*
                  (swap! *rate-limits* get-or-create-rate-limit
                         (reverse (str/split server-name #"\."))))]
      (when-let [gate (server-name->rate-limit gates server-name)]
        (tarry gate)))
    ;; propagate the request
    (client req)))

;; File cache middleware
;;
;; Built off of the example cache middleware
;;--------------------------------------------------------------------------------------------------
(defn req->f [{:keys [server-name uri query-string] :as req}]
  (let [c       (io/file "cache")
        [_ ext] (when uri (re-find #".*?\.(.*)\Z" uri))
        ext     (or ext "html")]
    (.mkdirs c)
    (io/file c (format "%s.%s" (sha/sha256 (str server-name uri query-string)) ext))))

(defn slurp-bytes
  "Read all bytes from the stream.
  Use for example when the bytes will be in demand after stream has been closed."
  [stream]
  (.getBytes (slurp stream) StandardCharsets/UTF_8))

(def if-modified-since-format
  (SimpleDateFormat. "E, d MMM y HH:mm:ss 'GMT'"))

(defn utc-normalize ^long [^long ms]
  (let [tz (java.util.TimeZone/getDefault)
        cal (java.util.GregorianCalendar/getInstance tz)]
    (- ms (. tz (getOffset (. cal (getTimeInMillis)))))))

(defn if-modified-since-date [^File f]
  (.format ^DateFormat if-modified-since-format
           (Date. (utc-normalize (.lastModified f)))))

(defn- cached-response
  "Look up the response in the cache using URL as the cache key.
  If the cache has the response, return the cached value.
  If the cache does not have the response, invoke the remaining middleware functions
  to perform the request and receive the response.
  If the response is successful (2xx) and is a GET, store the response in the cache.
  Return the response."
  ([client req]
   (let [method  (or (:method req) (:request-method req))
         cache-f (req->f req)]
     (if (and (= :get method)
              (:cache req true)
              (.exists cache-f))
       (do (log/infof "Cache hit on %s%s?%s" (:server-name req) (:uri req) (:query-string req))
           ;; The cache ignores URL parameters and some other stuff.  The correct "real browser"
           ;; behavior is to issue a request to the server, and only hit in the cache if the server
           ;; says to by returning a 304 response.
           (if (<= (- (System/currentTimeMillis)
                      (.lastModified cache-f))
                   (* 60 1000))
             (do (log/info "Cache for this request is under a minute old, forcing a hit")
                 (nippy/thaw-from-file cache-f))

             (let [date (if-modified-since-date cache-f)
                   _    (log/infof "Checking to see if resource has changed since '%s'" date)
                   resp (client (update req :headers merge
                                        {"if-modified-since" date
                                         "cache-control"     "max-age=120"}))]
               (if (= 304 (:status resp))
                 (do (log/info "Server responded 304 unchanged")
                     (nippy/thaw-from-file cache-f))

                 (let [resp (update resp :body slurp-bytes)]
                   (if (http/success? resp)
                     (nippy/freeze-to-file cache-f resp))

                   (log/infof "Server returned %s changed for %s%s" (:status resp)
                              (:server-name req) (:uri req))
                   resp)))))
       (do (log/infof "Cache miss on %s%s?%s" (:server-name req) (:uri req) (:query-string req))
           (let [resp (update (client req) :body slurp-bytes)]
             (when (and (http/success? resp) (= :get method))
               (nippy/freeze-to-file cache-f resp))
             resp))))))

(defn wrap-caching-middleware
  "Middleware are functions that add functionality to handlers.
  The argument client is a handler.
  This wrapper function adds response caching to the client."
  [client]
  (fn [req]
    (cached-response client req)))

(defn- apply-url-prefix [base-url url]
  (if (.startsWith url "//")
    (.replaceFirst url "//" "http://")
    (if (.startsWith url "/")
      (-> (beetle/parse-url base-url)
          (assoc :uri url)
          (beetle/unparse-url))
      (-> (beetle/parse-url base-url)
          (update :uri str "../" url)
          (beetle/unparse-url)))))

(defn get
  "Execute the given request, checking the local cache and working
  really hard to be nice.

  Returns the full response map.

  Note that the `:cache` option can be set to `false` in order to
  disable the cache behavior."
  [req]
  {:pre [(or (string? req)
             (beetle/request? req))]}
  (http/with-additional-middleware [#'wrap-rate-limiting-middleware
                                    #'wrap-caching-middleware]
    (let [req (if (string? req)
                    (beetle/->req {:url (beetle/parse-url req)})
                    req)
          url (beetle/unparse-url (:url req))
          resp (-> (beetle/get req)
                  :body
                  (StringReader.)
                  (html/html-resource))]

      (doseq [{{src :src} :attrs} (html/select resp [:script])
              :when src
              :let [src (apply-url-prefix url src)]]
        (log/infof "Fetching %s for browser emulation..." src)
        (http/get src))

      (doseq [{{href :href type :type} :attrs} (html/select resp [:script])
              :when href
              :when (= type "text/css")
              :let [href (apply-url-prefix url href)]]
        (log/infof "Fetching %s for browser emulation..." href)
        (http/get href))

      resp)))
