(ns rxcljs.transformers
  #?(:cljs (:require-macros
            [rxcljs.core :refer [go go-loop <! handle-rxval]]
            [rxcljs.transformers :refer [<<! <o! <p! <n! denodify..]]))
  (:require
   [clojure.core.async :as async :refer [close!]]
   [clojure.string :as s]
   #?@(:cljs [[goog.object :as go]
              [rxcljs.core :as rc]]
       :clj [[rxcljs.core :as rc :refer [go go-loop <!]]])))

#?(:cljs
   (defn promise?
     "Check obj is `js/Promise` instance"
     [obj]
     (and obj (fn? (.-then obj)))))

#?(:cljs
   (defn chan->promise
     "Resolve `chan` next value to [`js/Promise`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/promise)"
     [chan]
     (js/Promise.
      (fn [resolve reject]
        (go (handle-rxval
             [val (async/<! chan)]
             (resolve val)
             (reject @val)))))))

#?(:cljs
   (defn promise->chan
     "Transform [`js/Promise`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/promise) to `chan`"
     [promise]
     (let [chan (async/chan)]
       (.then
        promise
        (fn [val]
          (if val
            (async/put! chan val (fn [] (close! chan)))
            (close! chan)))
        #(async/put! chan (rc/RxError. %) (fn [] (close! chan))))
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
     #js {:next #(async/put! chan %)
          :error #(async/put! chan (rc/RxError. %) (fn [] (close! chan)))
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




#?(:cljs
   (defn denodify
     "Returns a function that will wrap the given `nodeFunction`.
  Instead of taking a callback, the returned function will return
  a `cljs.core.async/chan` whose fate is decided by the callback
  behavior of the given node function. The node function should
  conform to node.js convention of accepting a callback as last
  argument and calling that callback with error as the first
  argument and success value on the second argument.

  Arguments:

  * `f`: The function need be denodify.
  * `receiver`: Optional, if you pass a `receiver`, the `f` will be called
  as a method on the `receiver`. Default nil
  * `options`: Optional
      * `transform`: Optional, can transform callback result. Default:
          ```clojurescript
          (fn [err data]
            (cond err (rxerror err)
                  (nil? data) :nil
                  :else data))
          ```


  ```clojurescript
  (def read-file (denodify (.-readFile fs) fs))
  (def accessable? (denodify (.-access fs) :transform (fn [err data] (nil? err))))

  (go (try
        (let [content (<! (read-file \"myfile\" \"utf8\"))]
          (println \"The result of evaluating myfile.js\" (.toString content)))
        (catch :default err
          (prn 'Error reading file' err))))
  ```

  Note that if the node function is a method of some object, you
  can pass the object as the second argument like so:

  ```clojurescript
  (def redis-get (denodify (.-get redisClient) redisClient))

  (go (<! (redis-get \"foo\")))
  ```"
     ([f]
      (denodify f nil))
     ([f receiver]
      (denodify f receiver nil))
     ([f receiver & {:keys [transform]
                     :or {transform (fn [err data]
                                      (cond err (rc/rxerror err)
                                            (nil? data) :nil
                                            :else data))}}]
      (let [denodified-fn (fn [& args]
                            (let [chan (async/chan)
                                  close-chan #(close! chan)
                                  callback #(async/put! chan (apply transform %&) close-chan)
                                  final-args (concat args [callback])]
                              (.apply f receiver (into-array final-args))
                              chan))]
        (try
          (js/Object.defineProperty denodified-fn "length" #js {:configurable true :value (dec f.length)})
          (let [new-name (if (s/blank? (.-name f)) "denodified_fn" (str "denodified_" (.-name f)))]
            (js/Object.defineProperty denodified-fn "name" #js {:configurable true :value new-name}))
          (catch :default err
            (js/console.error err)))
        denodified-fn))))

#?(:clj
   (defmacro denodify..
     "```clojurescript
  (macroexpand-1 '(denodify.. fs.readFile \"foo\"))
  #=> ((denodify fs.readFile) \"foo\")

  (macroexpand-1 '(denodify.. redisClient -get \"foo\"))
  #=> ((denodify (.. redisClient -get) redisClient) \"foo\")
  ```"
     [o & rest]
     (let [[path args] (split-with #(and (symbol? %)
                                         (s/starts-with? (name %) "-"))
                                   rest)]
       (cond
         (empty? path)
         `((denodify ~o) ~@args)

         (= 1 (count path))
         `((denodify (.. ~o ~@path) ~o) ~@args)

         :else
         `((denodify (.. ~o ~@path) (.. ~o ~@(butlast path))) ~@args)))))

#_(macroexpand-1 '(denodify.. obj -a -b "test path"))

#?(:clj
   (defmacro <n!
     "```clojurescript
  (macroexpand-1 '(<n! redisClient -get \"foo\"))
  #=> (<! (denodify.. redisClient -get \"foo\"))
  ```"
     [& denodify-args]
     `(<! (rxcljs.transformers/denodify.. ~@denodify-args))))

#_(macroexpand-1 '(<n! obj -a -b "test path"))




(defn flat-chan
  "Flat nested chan to a value.

  ```clojurescript
  (is (= 1 (<! (flat-chan (go (go (go 1)))))))
  (is (= 1 (<! (flat-chan 1))))
  ```"
  [ch]
  (go-loop [c ch]
    (if (rc/chan? c)
      (recur (<! c))
      c)))

#?(:clj
   (defmacro <<!
     "Shortcut for (<! (flat-chan chan))"
     [ch]
     `(<! (flat-chan ~ch))))
