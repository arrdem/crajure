(defproject me.arrdem/crajure "0.3.4"
  :description "An extremely simple interface to scrape craigslist. How you use it may be against their T.O.S."
  :url "http://www.github.com/arrdem/crajure"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [enlive "1.1.6"]
                 [me.arrdem/rate-gate "1.3.1"]
                 [pandect "0.6.0"]]
  :deploy-repositories [["releases" :clojars]])
