(ns rxcljs.core-test
  (:require-macros
   [rxcljs.core :refer [go <! >!]])
  (:require
   [pjstadig.humane-test-output]
   [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
   [cljs.core.async :as async]
   [cljs.core.async.impl.buffers :as asyncb]
   [rxcljs.core :as rc :refer [RxError] :include-macros true]))

(defn -main [])




(deftest go-test
  (ct/async
   done
   (rc/go-let [e (ex-info "foo" {})
               ch (async/chan)]
     (is (= 1
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




(deftest take!-test
  (ct/async
   done
   (rc/go-let [fake-error (js/Error. "fake error")
               chan1 (async/go)
               chan2 (async/go 1)
               chan3 (async/go fake-error)
               chan4 (rc/go 1)
               chan5 (rc/go fake-error)
               chan6 (rc/go (throw fake-error))
               take! (fn [in]
                       (let [out (async/chan)]
                         (rc/take! in #(if %
                                         (do (async/put! out %)
                                             (async/close! out))
                                         (async/close! out)))
                         out))]

     (rc/<! (async/go))

     (is (= nil (async/<! (take! chan1))) "normal empty channel")

     (is (= 1 (async/<! (take! chan2))) "normal channel")
     (is (= nil (async/<! (take! chan2))) "closed channel")

     (is (= fake-error (async/<! (take! chan3))) "normal channel with exception")
     (is (= nil (async/<! (take! chan3))) "closed channel with excpetion ")

     (is (= 1 (async/<! (take! chan4))) "normal wrapped channel")
     (is (= nil (async/<! (take! chan4))) "closed wrapped channel")

     (is (= fake-error (async/<! (take! chan5))) "normal wrapped channel with exception")
     (is (= nil (async/<! (take! chan5))) "closed wrapped channel with exception")

     (is (= nil (async/<! (take! chan6))) "throwed normal wrapped channel")

     (done))))

(deftest poll!-test
  (ct/async
   done
   (rc/go-let [fake-error (js/Error. "fake error")
               chan1 (async/go)
               chan2 (async/go 1)
               chan3 (async/go fake-error)
               chan4 (rc/go 1)
               chan5 (rc/go fake-error)
               chan6 (rc/go (throw fake-error))]

     (rc/<! (async/go))

     (is (= nil (rc/poll! chan1)) "normal empty channel")

     (is (= 1 (rc/poll! chan2)) "normal channel")
     (is (= nil (rc/poll! chan2)) "closed channel")

     (is (= fake-error (rc/poll! chan3)) "normal channel with exception")
     (is (= nil (rc/poll! chan3)) "closed channel with excpetion ")

     (is (= 1 (rc/poll! chan4)) "normal wrapped channel")
     (is (= nil (rc/poll! chan4)) "closed wrapped channel")

     (is (= fake-error (rc/poll! chan5)) "normal wrapped channel with exception")
     (is (= nil (rc/poll! chan5)) "closed wrapped channel with exception")

     (is (= nil (rc/poll! chan6)) "throwed normal wrapped channel")

     (done))))




(deftest chan?
  (is (not (rc/chan? [])))
  (is (rc/chan? (async/chan))))




(deftest closed?
  (is (rc/closed? []))
  (is (not (rc/closed? (async/chan))))
  (is (rc/closed? (async/close! (async/chan)))))




(deftest clone-buf
  (let [bufs [(async/buffer 10)
              (async/dropping-buffer 18)
              (async/sliding-buffer 9)]
        [buf1 buf2 buf3] (map rc/clone-buf bufs)]

    (is (instance? asyncb/FixedBuffer buf1))
    (is (= 10 (.-n buf1)))

    (is (instance? asyncb/DroppingBuffer buf2))
    (is (= 18 (.-n buf2)))

    (is (instance? asyncb/SlidingBuffer buf3))
    (is (= 9 (.-n buf3)))

    (try
      (rc/clone-buf [])
      (is false)
      (catch :default err
        (is (instance? js/Error err))
        (is (= "Unsupported buffer type" err.message))))))
