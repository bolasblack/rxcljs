(ns rxcljs.operators
  #?(:cljs (:require-macros
            [adjutant.core :refer [def-]]
            [rxcljs.core :refer [go go-let go-loop <! >!]]
            [rxcljs.transformers :refer [<<!]]))
  (:require
   [clojure.core.async :as async]
   #?@(:clj [[adjutant.core :refer [def-]]
             [rxcljs.core :as rc :refer [go go-let go-loop <! >!]]
             [rxcljs.transformers :refer [<<!]]]
       :cljs [[rxcljs.core :as rc]])))

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
