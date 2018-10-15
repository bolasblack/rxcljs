# rxcljs

Because [`core.async`](https://github.com/clojure/core.async/) is too low level, let's make it a little bit sweeter.

## Why

`core.async` is great, I use it in many my own project.

But it doesn't has a mechanism to handle error gracefully.

And it cannot integrate with existing asynchronization solutions (e.g. `Promise`, `Observable`, `AsyncGenerator`...) in JavaScript world simply.

Compares with `Promise`/`Observable`, the operators is also lacking.

So I hope I can write some code to resolve those problems.

## Install

```clojure
;; deps.edn
{:deps {rxcljs {:git/url "https://github.com/bolasblack/rxcljs"
                :sha "[git commit hash]"}}}
```

## Basic usage

```clojure
(ns cljs.user
  (:require-macros '[rxcljs.core :refer [go <! >!]])
  (:require '[cljs.core.async :as async]
            '[rxcljs.core :as rc]))

(def error (js/Error.))

(defn- fn1 []
  (go (throw error)))

(defn- fn2 []
  (go (try
        (<! (fn1))
        (pr "code won't be executed")
        (catch js/Error err
          (pr err)
          1))))

(defn -main []
  (go (= (rc/RxError. error)
         (rc/rxerror error)
         (async/<! (fn1)))
      (= (rc/RxNext. 1)
         (rc/rxnext 1)
         (async/<! (fn2)))))
```
