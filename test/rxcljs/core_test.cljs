(ns rxcljs.core-test
  (:require-macros
   [rxcljs.core :refer [go <! >!]])
  (:require
   [pjstadig.humane-test-output]
   [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
   [cljs.core.async :as async]
   [rxcljs.core :as rc :refer [RxNext RxError] :include-macros true]))

(defn -main [])




(defn- close-to? [expected actual &
                  {:keys [deviate]
                   :or {deviate 100}}]
  (some #(= actual %)
        (range (- expected deviate)
               (+ expected deviate))))

(defn- create-chan [duration & args]
  (let [chan (async/chan)]
    (go (async/<! (async/timeout duration))
        (>! chan (into [1] args))
        (async/<! (async/timeout duration))
        (>! chan (into [2] args))
        (async/<! (async/timeout duration))
        (>! chan (into [3] args))
        (async/close! chan))
    chan))

(deftest wait-multiple-chan
  (ct/async
   done
   (rc/go-let
     [start (js/Date.now)
      chan (async/map
            #(conj %& (- (js/Date.now) start))
            [(create-chan 100 :a1 :a2)
             (create-chan 200 :b1 :b2)])

      d1 (async/<! chan)
      _ (is (close-to? 200 (first d1)))
      _ (is (= (next d1)
               '([1 :a1 :a2]
                 [1 :b1 :b2])))

      d2 (async/<! chan)
      _ (is (close-to? 400 (first d2)))
      _ (is (= (next d2)
               '([2 :a1 :a2]
                 [2 :b1 :b2])))

      d3 (async/<! chan)
      _ (is (close-to? 600 (first d3)))
      _ (is (= (next d3)
               '([3 :a1 :a2]
                 [3 :b1 :b2])))]

     (done))))




(deftest go-test
  (ct/async
   done
   (rc/go-let [e (ex-info "foo" {})
               ch (async/chan)]
     (is (= (rc/rxnext 1)
            (async/<! (rc/go 1))))
     (is (= (rc/rxerror e)
            (async/<! (rc/go (throw e)))))
     (is (= (rc/rxerror e)
            (async/<! (rc/go (rc/rxerror e)))))
     (async/put! ch (rc/rxerror e))
     (async/<!
      (rc/go
        (try
          (<! ch)
          (is false)
          (catch js/Error err
            (is (= e err))))))
     (done))))

(deftest go-test-with-non-standard-error
  (ct/async
   done
   (rc/go-let [e [123 nil]
               ch (async/chan)]
     (async/put! ch e)
     (is (= e (<! (rc/go (<! ch)))))
     (async/put! ch (rc/rxerror e))
     (rc/go
       (try
         (<! ch)
         (is false)
         (catch :default err
           (is (= e err)))))
     (done))))




(defn read-both [ch-a ch-b]
  (rc/go-let [a (<! ch-a)
              b (<! ch-b)]
    (is false)
    [a b]))

(deftest <!-test-1
  (ct/async
   done
   (rc/go-let [e (ex-info "foo" {})
               ch-a (go (throw e))
               ch-b (go 1)
               res (async/<! (read-both ch-a ch-b))]
     (is (= (rc/rxerror e) res))
     (done))))




(deftest chan?
  (is (not (rc/chan? [])))
  (is (rc/chan? (async/chan))))
