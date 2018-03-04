(ns crajure.http
  "Helpers for making HTTP request with faked user-agent strings,
  proxies, caches and other such awesomeness."
  (:refer-clojure :exclude [get])
  (:require [beetle.core :as beetle]
            [beetle.middleware.file-cache :refer [->caching-middleware]]
            [beetle.middleware.rate-limit :refer [wrap-rate-limiting-middleware]]
            [clj-http.client :as http]
            [clojure.tools.logging :as log]
            [net.cgrand.enlive-html :as html])
  (:import java.io.StringReader))

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
  (http/with-additional-middleware [wrap-rate-limiting-middleware
                                    (->caching-middleware "target/request-cache")]
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
