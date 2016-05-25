crajure
=======

Very Easy Craigslist Scraping for Clojure.
----

We've been using it for data vis, BI, and some other things.

[![Clojars Project](http://clojars.org/me.arrdem/crajure/latest-version.svg)](http://clojars.org/me.arrdem/crajure)

Forked from [escherize/crajure](https://github.com/escherize/crajure) with no particular intent to
contribute back.

Differences from upstream
--
- Section keywords are now namespaced, to deal with the fact that Craigslist has several different
  subcategories such as "housing - rooms to let".
- `:category` key now missing from responses, since due to UI changes in Craigslist it is no longer
  trivial to infer this. It should be able to recover this information from the listing URLs in the
  future.
- `:item-url` has been renamed to `:url`.
- `:preview` being the URL for the item's first picture has been added.
- `:address` being the street address for the item if available has been added.

Usage
---
To search for fixie bikes in san francisco, simply supply the space delimited query string, area
code (i.e. `:sfbay`), and site section (i.e. `:for-sale/all`); like so:

```{clojure}
(query-cl "fixie bike" :sfbay :for-sale/all)
;=> 
   ({:price 2500,
     :title "2013 Giant Glory 2 Medium *price drop*",
     :date "2014-10-22 21:20",
     :region "morgan hill",
     :item-url "http://sfbay.craigslist.org/sby/bik/4713638395.html"}
    {:price 2500,
     :title "FS: Cinelli Mash Original Bolt Complete 53cm",
     :date "2014-10-20 11:57",
     :region "san jose south",
     :item-url "http://sfbay.craigslist.org/sby/bik/4681252594.html"}
    ...)
```

Also, one may call query-cl with a map like so:

```{clojure}
(query-cl {:query "fixie bike"
           :area "sfbay"
           :section :for-sale/all})
;=> ({:price 2500,
     :title "2013 Giant Glory 2 Medium *price drop*",
     :date "2014-10-22 21:20",
     :region "morgan hill",
     :item-url "http://sfbay.craigslist.org/sby/bik/4713638395.html"}
    {:price 2500,
     :title "FS: Cinelli Mash Original Bolt Complete 53cm",
     :date "2014-10-20 11:57",
     :region "san jose south",
     :item-url "http://sfbay.craigslist.org/sby/bik/4681252594.html"}
    ...)
```

Why Crajure?
----
One interesting benefit to using this library is we can query craigslist for the entire earth using
`:all` as the area-code.  We can also look at every section of the site with one query just the same
way, by using `:all` in the site-section field.  These queries can hit thousands of webpages so of
course it's not exactly reccomended when online speed is nessicary.

```{clojure}
(query-cl "bicycle" :all :all)
;=>  lots of maps.
```


