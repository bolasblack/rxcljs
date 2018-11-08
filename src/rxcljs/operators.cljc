(ns rxcljs.operators
  (:refer-clojure :exclude [map])
  #?(:cljs (:require-macros
            [adjutant.core :refer [if-cljs def-]]
            [rxcljs.core :refer [go go-let go-loop <! >!]]
            [rxcljs.transformers :refer [<<!]]))
  (:require
   [clojure.core.async :as async :include-macros true]
   #?@(:clj [[adjutant.core :refer [if-cljs def-]]
             [rxcljs.core :as rc :refer [go go-let go-loop <! >!]]
             [rxcljs.transformers :refer [<<!]]]
       :cljs [[rxcljs.core :as rc]
              [rxcljs.transformers]])))

(defn xfwrap
  "wrap/unwrap RxError for transducers automatically

  (let [[head tail] (xfwrap)]
     (apply comp head (concat xforms [tail])))"
  []
  (let [reducer-ref (volatile! nil)]
    [(fn [rf]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (if (rc/rxerror? input)
            (@reducer-ref result input)
            (try
              (rf result (if (rc/rxval? input) @input input))
              (catch :default e
                (@reducer-ref result (rc/rxerror e))))))))
     (fn [rf]
       (vreset! reducer-ref rf)
       rf)]))

(defn xfcomp
  "Drop-in replacement for comp, wrap RxError for transducers automatically"
  [& xforms]
  (let [[head tail] (xfwrap)]
    (apply comp head (concat xforms [tail]))))

(defn pipe
  "Takes elements from the channel and transform them with multiple
  transducers, then supplies the result to the returned channel. The
  result channel will be closed when the input channel closes.

  (go-let [chan (pipe (rt/from [1 2 3])
                  (filter odd?)
                  (map inc))]
    (is (= 2 (<! chan))))"
  [chan & xforms]
  (let [cloned-buf (rc/clone-buf (.-buf chan))
        cloned-chan (async/chan cloned-buf (apply xfcomp xforms))]
    (async/pipe chan cloned-chan)
    cloned-chan))

(defn concurrency
  "Takes up to three arguments:

  * input (coll/channel): input values
  * limit (number): limit how many values are allowed to be transfrom at the
    same time
  * f (function): transform value from source, can return plain value or channel

  Returns a channel which will receive the transformed value, the value may not
  match the order from the input. Any thrown exception willn't abort the execution
  of the progress."
  [input limit f]
  (let [src-chan (if (rc/chan? input) input (async/to-chan input))
        dst-chan (async/chan limit)]
    (go-let [tasks (doall (repeatedly
                           limit
                           (fn []
                             (go-loop []
                               (let [closed? (volatile! false)]
                                 (try
                                   (if-let [data (<<! src-chan)]
                                     (do (let [r (<<! (f data))]
                                           (>! dst-chan r))
                                         (recur))
                                     (vreset! closed? true))
                                   (catch :default err
                                     (>! dst-chan (rc/rxerror err))))
                                 (when-not @closed?
                                   (recur)))))))]
      (doseq [task tasks]
        (<! task))
      (async/close! dst-chan))
    dst-chan))

;; from
;; https://github.com/clojure/core.async/blob/83ca922f618fb8b1a3affe7ab8b6b62b80e083e8/src/main/clojure/cljs/core/async.cljs#L680
;; https://github.com/clojure/core.async/blob/83ca922f618fb8b1a3affe7ab8b6b62b80e083e8/src/main/clojure/clojure/core/async.clj#L918
(defn map
  "Like core.async/map, but support RxError

  Will park if f returns a channel, the first value of the returned
  channel will be supplied to output channel."
  ([f chs] (map f chs nil))
  ([f chs buf-or-n]
   (let [chs (vec chs)
         out (async/chan buf-or-n)
         cnt (count chs)
         rets (object-array cnt)
         dchan (async/chan 1)
         dctr (atom nil)
         done (mapv (fn [i]
                      (fn [err ret]
                        (aset rets i (if err (rc/rxerror err) ret))
                        (when (zero? (swap! dctr dec))
                          (rc/put!
                           dchan
                           (if-cljs
                            (.slice rets 0)
                            (java.util.Arrays/copyOf rets cnt))))))
                    (range cnt))]
     (go-loop []
       (reset! dctr cnt)
       (dotimes [i cnt]
         (try
           (async/take! (chs i) #(if (rc/rxerror? %)
                                   ((done i) (deref %))
                                   ((done i) nil %)))
           (catch :default e
             ((done i) e))))
       (let [rets (<! dchan)]
         (cond
           (some rc/rxerror? rets)
           (do (async/>! out (first (filter rc/rxerror? rets)))
               (rc/close! out))

           (some nil? rets)
           (rc/close! out)

           :else (do
                   (try
                     (let [res (<<! (apply f rets))]
                       (>! out res))
                     (catch :default e
                       (>! out (rc/rxerror e))))
                   (recur)))))
     out)))
