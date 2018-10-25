(ns rxcljs.core
  (:refer-clojure :exclude [update])
  #?(:cljs (:require-macros
            [rxcljs.core :refer [go go-loop go-let handle-rxval >! <!]]))
  (:require
   #?(:cljs [clojure.core.async.impl.buffers :refer [FixedBuffer DroppingBuffer SlidingBuffer]])
   [clojure.core.async.impl.protocols :as async-protocols]
   [clojure.core.async :as async :refer [close!]]
   [adjutant.core :as ac :include-macros true])
  #?(:clj (:import [clojure.lang IDeref]
                   [clojure.core.async.impl.buffers FixedBuffer DroppingBuffer SlidingBuffer])))


(defprotocol RxCljsChanValue
  (update [self f]))

(defrecord RxNext [value]
  RxCljsChanValue
  (update [self f]
    (RxNext. (f (:value self))))
  IDeref
  #?(:cljs (-deref [record] (:value record))
     :clj (deref [record] (:value record))))

(defrecord RxError [error]
  RxCljsChanValue
  (update [self f]
    (RxError. (f (:error self))))
  IDeref
  #?(:cljs (-deref [record] (:error record))
     :clj (deref [record] (:error record))))

(defn rxnext? [a]
  (instance? RxNext a))

(defn rxnext [a]
  (if (rxnext? a) a (RxNext. a)))

(defn rxerror? [a]
  (instance? RxError a))

(defn rxerror [a]
  (if (rxerror? a) a (RxError. a)))

(defn rxval? [a]
  (satisfies? RxCljsChanValue a))

(defn rxval [a]
  (if (rxval? a) a (rxnext a)))




#?(:clj
   (defmacro handle-rxval
     ([bindings val-handler]
      `(handle-rxval ~bindings ~val-handler nil nil))
     ([bindings val-handler err-handler]
      `(handle-rxval ~bindings ~val-handler ~err-handler nil))
     ([bindings val-handler err-handler else-handler]
      (ac/assert-args
       (vector? bindings) "a vector for its binding"
       (= 2 (count bindings)) "exactly 2 forms in binding vector")
      (let [[v-sym] bindings
            err-cause (if err-handler err-handler val-handler)
            else-cause (if else-handler else-handler v-sym)]
        `(let ~bindings
           (cond
             (rxerror? ~v-sym) ~err-cause
             (rxval? ~v-sym) ~val-handler
             :else ~else-cause))))))

#_(macroexpand '(handle-rxval
                 [eval-expr (ac/if-cljs
                             (cljs.core.async/<! ~ch)
                             (clojure.core.async/<! ~ch))]
                 (deref eval-expr)
                 (throw (deref eval-expr))))




#?(:clj
   (defmacro go [& body]
     (let [wrapped-body
           `(try
              (rxval (do ~@body))
              (catch (ac/if-cljs :default Throwable) e#
                (rxerror e#)))]
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
       @eval-expr#
       (throw @eval-expr#))))

#_(macroexpand-1 '(<! ch))

#?(:clj
   (defmacro >! [& args]
     `(ac/if-cljs
       (cljs.core.async/>! ~@args)
       (clojure.core.async/>! ~@args))))




(defn chan? [a]
  (satisfies? async-protocols/ReadPort a))




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
