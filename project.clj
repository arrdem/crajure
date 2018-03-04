(defproject me.arrdem/crajure "_"
  :description "An extremely simple interface to scrape craigslist. How you use it may be against their T.O.S."
  :url "http://www.github.com/arrdem/crajure"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [enlive "1.1.6"]
                 [me.arrdem/rate-gate "1.3.1"]
                 [pandect "0.6.0"]]

  :deploy-repositories [["releases" :clojars]]

  :plugins [[me.arrdem/lein-git-version "[2.0.0,3.0.0)"]]

  :git-version {:status-to-version
                (fn [{:keys [tag version branch ahead ahead? dirty?] :as git}]
                  (if (and tag (not ahead?) (not dirty?))
                    (do (assert (re-find #"\d+\.\d+\.\d+" tag)
                                "Tag is assumed to be a raw SemVer version")
                        tag)
                    (if (and tag (or ahead? dirty?))
                      (let [[_ prefix patch] (re-find #"(\d+\.\d+)\.(\d+)" tag)
                            patch            (Long/parseLong patch)
                            patch+           (inc patch)]
                        (format "%s.%d-%s-SNAPSHOT" prefix patch+ branch))
                      "0.1.0-SNAPSHOT")))})
