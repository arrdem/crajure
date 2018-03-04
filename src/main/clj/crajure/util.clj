(ns crajure.util
  "leftover bits and bats")

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
