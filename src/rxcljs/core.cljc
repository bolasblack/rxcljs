(ns rxcljs.core
  (:refer-clojure :exclude [update])
  #?(:cljs (:require-macros
            [rxcljs.core :refer [go go-loop go-let handle-rxval >! <! <!!]]))
  (:require
   #?(:cljs [clojure.core.async.impl.buffers :refer [FixedBuffer DroppingBuffer SlidingBuffer]])
   [clojure.core.async.impl.protocols :as async-protocols]
   [clojure.core.async :as async]
   [adjutant.core :as ac :include-macros true])
  #?(:clj (:import [clojure.lang IDeref]
                   [clojure.core.async.impl.buffers FixedBuffer DroppingBuffer SlidingBuffer])))


(defprotocol RxCljsChanValue
  (update [self f]))

(defrecord RxError [error]
  RxCljsChanValue
  (update [self f]
    (RxError. (f (:error self))))
  IDeref
  #?(:cljs (-deref [record] (:error record))
     :clj (deref [record] (:error record))))

(defn rxerror? [a]
  (instance? RxError a))

(defn rxerror [a]
  (if (rxerror? a) a (RxError. a)))

(defn rxval? [a]
  (satisfies? RxCljsChanValue a))

(defn safely-unwrap-rxval
  "Safely unwrap any value

  * if v is RxError, return nil
  * else, return v"
  [v]
  (if (rxerror? v) nil v))




(defn chan? [a]
  (satisfies? async-protocols/ReadPort a))




(defn closed? [a]
  (if (chan? a)
    (async-protocols/closed? a)
    true))




#?(:clj
   (defmacro handle-rxval
     ([bindings err-cause]
      `(handle-rxval ~bindings nil ~err-cause))
     ([bindings val-cause err-cause]
      (ac/assert-args
       (vector? bindings) "a vector for its binding"
       (= 2 (count bindings)) "exactly 2 forms in binding vector")
      (let [[v-sym] bindings
            val-cause (or val-cause v-sym)]
        `(let ~bindings
           (if (rxerror? ~v-sym)
             ~err-cause
             ~val-cause))))))

#_(macroexpand '(handle-rxval
                 [eval-expr (ac/if-cljs
                             (cljs.core.async/<! ~ch)
                             (clojure.core.async/<! ~ch))]
                 @eval-expr
                 (throw @eval-expr)))




#?(:clj
   (defmacro go [& body]
     (let [catch-clause (if (:ns &env)  ; ClojureScript
                          '(catch :default e# (rxcljs.core/rxerror e#))
                          '(catch Throwable e# (rxcljs.core/rxerror e#)))
           wrapped-body `(try ~@body ~catch-clause)]
       `(ac/if-cljs
         (cljs.core.async/go ~wrapped-body)
         (clojure.core.async/go ~wrapped-body)))))

#?(:clj
   (defmacro go-loop [binding & body]
     `(go (loop ~binding ~@body))))

#?(:clj
   (defmacro go-let [binding & body]
     `(go (let ~binding ~@body))))

#?(:clj
   (defmacro <! [ch]
     `(handle-rxval
       [eval-expr# (ac/if-cljs
                    (cljs.core.async/<! ~ch)
                    (clojure.core.async/<! ~ch))]
       (throw @eval-expr#))))

#_(macroexpand-1 '(<! ch))

#?(:clj
   (defmacro >!
     [port val]
     `(ac/if-cljs
       (cljs.core.async/>! ~port ~val)
       (clojure.core.async/>! ~port ~val))))




#?(:cljs
   (defn <!!* [ch]
     (let [env-warning "[rxcljs.core] <!! only supported in Node.js and require installed https://www.npmjs.com/package/deasync"
           deasync (try
                     (js/require "deasync")
                     (catch js/ReferenceError err
                       (throw (js/RuntimeError. env-warning))))
           result-ref (volatile! {:done? false :value nil})]
       (when (closed? ch) nil)
       (cljs.core.async/take! ch #(vreset! result-ref {:done? true :value %}))
       (.loopWhile deasync #(not (:done? @result-ref)))
       (handle-rxval
        [value (:value @result-ref)]
        (throw @value)))))

#?(:clj
   (defmacro <!!
     "takes a val from port. Will return nil if closed. Will block
  if nothing is available.

  Use [deasync](https://www.npmjs.com/package/deasync) to block
  event loop in Node.js, and not supported in other JavaScript
  runtime"
     [port]
     `(ac/if-cljs
       (rxcljs.core/<!!* ~port)
       (clojure.core.async/<!! ~port))))




(defn take!
  "Like clojure.core.async/take!, but support RxError"
  ([port fn1] (take! port fn1 true))
  ([port fn1 on-caller?]
   (async/take!
    port
    #(fn1 (safely-unwrap-rxval %))
    on-caller?)))

(def put! "Alias of clojure.core.async/put!" async/put!)

(defn poll!
  "Like clojure.core.async/poll!, but support RxError"
  [port]
  (safely-unwrap-rxval (async/poll! port)))

(def offer! "Alias of clojure.core.async/offer!" async/offer!)

(def close! "Alias of clojure.core.async/close!" async/close!)




(defn clone-buf [buf]
  (cond
    (instance? FixedBuffer buf)
    (async/buffer (.-n buf))

    (instance? DroppingBuffer buf)
    (async/dropping-buffer (.-n buf))

    (instance? SlidingBuffer buf)
    (async/sliding-buffer (.-n buf))

    :else
    (ac/error! "Unsupported buffer type")))
