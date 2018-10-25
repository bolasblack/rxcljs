(ns rxcljs.operators
  (:require-macros
   [adjutant.core :refer [def-]]
   [rxcljs.core :refer [go go-let go-loop <! >!]])
  (:require
   [clojure.core.async :as async]
   [rxcljs.core :as rc]))

(defn xfwrap
  "wrap/unwrap RxNext and RxError for transducers automatically

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
              (rf result (if (rc/rxnext? input) @input input))
              (catch :default e
                (@reducer-ref result (rc/rxerror e))))))))
     (fn [rf]
       (vreset! reducer-ref rf)
       ((map rc/rxnext) rf))]))

(defn xfcomp
  "Drop-in replacement for comp, wrap/unwrap RxNext and RxError for transducers automatically"
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
    (is (= 2 (<! chan)))
    (is (= (rc/rxnext 4) (async/<! chan))))"
  [chan & xforms]
  (let [cloned-buf (rc/clone-buf (.-buf chan))
        cloned-chan (async/chan cloned-buf (apply xfcomp xforms))]
    (async/pipe chan cloned-chan)
    cloned-chan))

(defn limit-map [f source limit-chan]
  (let [src-chan (if (rc/chan? source)
                   source
                   (async/to-chan source))
        dst-chan (async/chan)
        wait-all-put-chan (volatile! (async/to-chan [:start]))]
    (go-loop []
      (<! limit-chan)
      (if-let [data (<! src-chan)]
        (let [r (f data)]
          (if (rc/chan? r)
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
            (async/close! dst-chan))))
    dst-chan))
