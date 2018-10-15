(ns rxcljs.core
  (:refer-clojure :exclude [update])
  #?(:cljs (:require-macros
            [rxcljs.core :refer [go go-loop go-let handle-rxval >! <! <<! <p! denodify..]]))
  (:require
   #?@(:cljs [[goog.object :as go]
              ["util" :refer [promisify]]])
   [clojure.core.async.impl.protocols]
   [clojure.core.async :as async :refer [close!]]
   [clojure.string :as s]
   [utils.core :as uc :include-macros true])
  #?(:clj (:import [clojure.lang IDeref])))




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
      (uc/assert-args
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
                 [eval-expr (uc/if-cljs
                             (cljs.core.async/<! ~ch)
                             (clojure.core.async/<! ~ch))]
                 (deref eval-expr)
                 (throw (deref eval-expr))))




#?(:clj
   (defmacro go [& body]
     (let [wrapped-body
           `(try
              (rxval (do ~@body))
              (catch (uc/if-cljs :default Throwable) e#
                (rxerror e#)))]
       `(uc/if-cljs
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
       [eval-expr# (uc/if-cljs
                    (cljs.core.async/<! ~ch)
                    (clojure.core.async/<! ~ch))]
       @eval-expr#
       (throw @eval-expr#))))

#_(macroexpand-1 '(<! ch))

#?(:clj
   (defmacro >! [& args]
     `(uc/if-cljs
       (cljs.core.async/>! ~@args)
       (clojure.core.async/>! ~@args))))




(defn chan? [a]
  (satisfies? clojure.core.async.impl.protocols/ReadPort a))




#?(:cljs
   (defn promise? [obj]
     (and obj (fn? (.-then obj)))))

#?(:cljs
   (defn chan->promise
     "Resolve `chan` next value to [`js/Promise`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/promise)"
     [chan]
     (js/Promise.
      (fn [resolve reject]
        (go (handle-rxval
             [val (async/<! chan)]
             (resolve @val)
             (reject @val)
             (resolve val)))))))

#?(:cljs
   (defn promise->chan
     "Transform [`js/Promise`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/promise) to `chan`"
     [promise]
     (let [chan (async/chan)]
       (.then
        promise
        (fn [val]
          (if val
            (async/put! chan (RxNext. val) (fn [] (close! chan)))
            (close! chan)))
        #(async/put! chan (RxError. %) (fn [] (close! chan))))
       chan)))

#?(:clj
   (defmacro <p!
     "Like `<!`, but receive [`js/Promise`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/promise)"
     [promise]
     `(<! (promise->chan ~promise))))




#?(:cljs
   (defn chan->observer
     "Transform `chan` to [`Observable`](https://rxjs-dev.firebaseapp.com/api/index/class/Observable)"
     [chan]
     #js {:next #(async/put! chan (RxNext. %))
          :error #(async/put! chan (RxError. %) (fn [] (close! chan)))
          :complete #(close! chan)}))

#?(:cljs
   (defn observable->chan
     "Transform [`Observable`](https://rxjs-dev.firebaseapp.com/api/index/class/Observable) to `chan`"
     [ob]
     (let [chan (async/chan)]
       (.subscribe ob (chan->observer chan))
       chan)))

#?(:clj
   (defmacro <o!
     "Like `<!`, but receive [`Observable`](https://rxjs-dev.firebaseapp.com/api/index/class/Observable)"
     [ob]
     `(<! (observerable->chan ~ob))))




#?(:cljs
   (defn chan->async-iterator
     "Transform `chan` to [`AsyncIterator`](https://github.com/tc39/proposal-async-iteration)"
     [chan]
     (let [res #js {:next #(.then
                            (chan->promise chan)
                            (fn [res]
                              (if (nil? res)
                                #js {:done true :value nil}
                                #js {:done false :value res})))}]
       (try (go/set res js/Symbol.asyncIterator (fn [] res)))
       res)))




(defn flat-chan [ch]
  (go-loop [c ch]
    (if (chan? c)
      (recur (<! c))
      c)))

#?(:clj
   (defmacro <<! [ch]
     `(<! (flat-chan ~ch))))




(defn limit-map [f source limit-chan]
  (let [src-chan (if (chan? source)
                   source
                   (async/to-chan source))
        dst-chan (async/chan)
        wait-all-put-chan (volatile! (async/to-chan [:start]))]
    (go-loop []
      (<! limit-chan)
      (if-let [data (<! src-chan)]
        (let [r (f data)]
          (if (chan? r)
            (vswap! wait-all-put-chan
                    (fn [old-chan]
                      (go-let [resp (<! r)]
                        (<! old-chan)
                        (>! dst-chan resp))))
            (vswap! wait-all-put-chan
                    (fn [old-chan]
                      (go (<! old-chan)
                          (>! dst-chan r)))))
          (recur))
        (do (<! @wait-all-put-chan)
            (close! dst-chan))))
    dst-chan))




#?(:cljs
   (defn denodify
     "Returns a function that will wrap the given `nodeFunction`.
  Instead of taking a callback, the returned function will return
  a `cljs.core.async/chan` whose fate is decided by the callback
  behavior of the given node function. The node function should
  conform to node.js convention of accepting a callback as last
  argument and calling that callback with error as the first
  argument and success value on the second argument.

  If the `nodeFunction` calls its callback with multiple success
  values, the fulfillment value will be an array of them.

  If you pass a `receiver`, the `nodeFunction` will be called as a
  method on the `receiver`.

  Example of promisifying the asynchronous `readFile` of node.js `fs`-module:

  ```clojurescript
  (def read-file (denodify (.-readFile fs)))

  (go (try
        (let [content (<? (read-file \"myfile\" \"utf8\") :failure? first)]
          (println \"The result of evaluating myfile.js\" (.toString content)))
        (catch js/Error err
          (prn 'Error reading file' err))))
  ```

  Note that if the node function is a method of some object, you
  can pass the object as the second argument like so:

  ```clojurescript
  (def redis-get (denodify (.-get redisClient) redisClient))

  (go (<! (redis-get \"foo\")))
  ```
  "
     ([f]
      (denodify f nil))
     ([f receiver]
      (let [promisify-fn (promisify f)
            denodified-fn (fn denodified-fn [& args]
                            (go (<p! (.apply promisify-fn receiver (apply array args)))))]
        (try
          (js/Object.defineProperty
           denodified-fn
           "length"
           #js {:configurable true :value (if (zero? (.-length promisify-fn))
                                            0
                                            (dec (.-length promisify-fn)))})
          (let [new-name (if (s/blank? (.-name f))
                           "denodified_fn"
                           (str "denodified_" (.-name f)))]
            (js/Object.defineProperty
             denodified-fn
             "name"
             #js {:configurable true :value new-name}))
          (catch :default err
            (js/console.error err)))
        denodified-fn))))

#?(:clj
   (defmacro denodify..
     "```clojurescript
  (go (<! (denodify.. fs.readFile \"foo\")))
  (macroexpand-1 '(denodify.. fs.readFile \"foo\"))
  #=> ((denodify fs.readFile) \"foo\")

  (go (<! (denodify.. redisClient -get \"foo\")))
  (macroexpand-1 '(denodify.. redisClient -get \"foo\"))
  #=> ((denodify (.. redisClient -get) redisClient) \"foo\")
  ```"
     ([o & _path]
      (let [[path args] (split-with #(and (symbol? %)
                                          (s/starts-with? (name %) "-"))
                                    _path)]
        (cond
          (empty? path)
          `((denodify ~o) ~@args)

          (= 1 (count path))
          `((denodify (.. ~o ~@path) ~o) ~@args)

          :else
          `((denodify (.. ~o ~@path) (.. ~o ~@(butlast path))) ~@args))))))
