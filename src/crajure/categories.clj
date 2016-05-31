(ns crajure.categories)

(def categories
  {:community {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "ccc"}}}
   :events    {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "eee"}}}
   :for-sale  {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "sss"}}}
   :gigs      {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "ggg"}}}
   :housing   {:type      ::category
               :default   :all
               :selectors {:all        {:type ::selector
                                        :path "hhh"}
                           :apartments {:type ::selector
                                        :path "apa"}
                           :office     {:type ::selector
                                        :path "off"}
                           :sublets    {:type ::selector
                                        :path "sub"}
                           :rooms      {:type ::selector
                                        :path "roo"}}}
   :jobs      {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "jjj"}}}
   :personals {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "ppp"}}}
   :resumes   {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "rrr"}}}
   :services  {:type      ::category
               :default   :all
               :selectors {:all {:type ::selector
                                 :path "bbb"}}}
   :all       [[:community :all]
               [:events :all]
               [:for-sale :all]
               [:gigs :all]
               [:housing :all]
               [:jobs :all]
               [:personals :all]
               [:resumes :all]
               [:services :all]]})

(defn category? [o]
  (and (map? o)
       (= (:type o) ::category)
       (:selectors o)))

(defn as-category [o]
  {:post [(category? %)]}
  (cond (category? o) o
        (keyword? o)  (get categories o)
        :else         (throw
                       (ex-info "Unsupported arg type!"
                                {:val   o
                                 :class (class o)}))))

(defn selector? [o]
  (and (map? o)
       (= (:type o) ::selector)
       (string? (:path o))))

(defn as-selector [o]
  {:post [(selector? %)]}
  (or (when (selector? o) o)

      (when (and (keyword? o)
                 (namespace o))
        (if-let [cat (as-category (keyword (namespace o)))]
          (if-let [sel (get-in cat [:selectors (keyword (name o))])]
            sel)))

      (when (keyword? o)
        (if-let [cat (as-category o)]
          (if-let [d (:default cat)]
            (if-let [sel (get-in cat [:selectors d])]
              sel))))

      (when (vector? o)
        (if-let [cat (as-category (first o))]
          (if-let [sel (get-in cat [:selectors (second o)])]
            sel)))

      (throw
       (ex-info "Unsupported arg type!"
                {:val   o
                 :class (class o)}))))

;; FIXME: lol overspecialized
(defn as-selectors [o]
  {:post [(every? selector? %)]}
  (if-not (= o :all)
    [(as-selector o)]
    (map as-selector (get categories o))))
