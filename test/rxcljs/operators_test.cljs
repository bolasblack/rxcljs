(ns rxcljs.operators-test
  (:require-macros
   [rxcljs.core :refer [go go-let go-loop <! >!]])
  (:require
   [pjstadig.humane-test-output]
   [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
   [cljs.core.async :as async]
   [rxcljs.operators :as ro]))

(deftest limit-map
  (ct/async
   done
   (go-let [limit-chan (async/chan 5)
            last-job-info (volatile! nil)
            last-job-result (volatile! nil)
            job-ids (range 10)
            map-chan (ro/limit-map
                      #(do (vreset! last-job-info %)
                           (* 2 %))
                      job-ids
                      limit-chan)]

     (go-loop []
       (let [r (<! map-chan)]
         (vreset! last-job-result r)
         (recur)))

     (is (= nil @last-job-info))
     (is (= nil @last-job-result))

     (dotimes [n 5]
       (>! limit-chan n))
     (<! (async/timeout 0))
     (is (= 4 @last-job-info))
     (is (= 8 @last-job-result))

     (<! (async/timeout 0))
     (is (= 4 @last-job-info))
     (is (= 8 @last-job-result))

     (dotimes [n 2]
       (>! limit-chan n))
     (<! (async/timeout 0))
     (is (= 6 @last-job-info))
     (is (= 12 @last-job-result))

     (done))))
