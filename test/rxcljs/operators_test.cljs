(ns rxcljs.operators-test
  (:require-macros
   [rxcljs.core :refer [go go-let go-loop <! >!]])
  (:require
   [pjstadig.humane-test-output]
   [cljs.test :as ct :refer-macros [deftest testing is are] :include-macros true]
   [cljs.core.async :as async]
   [rxcljs.core :as rc]
   [rxcljs.operators :as ro]))

(def test-xf-rxwrap-comp-error
  (js/Error. "test-xf-rxwrap-comp-error"))

(def test-xf-rxwrap-comp-input
  [1 2 3
   (rc/rxnext 1) (rc/rxnext 2) (rc/rxnext 3)
   [] (list)
   (rc/rxnext []) (rc/rxnext (list))
   (rc/rxerror test-xf-rxwrap-comp-error) test-xf-rxwrap-comp-error])

(defn test-xf-rxwrap-comp-output [res]
  (let [c= (fn [v1] (fn [v2] (= v1 v2)))
        m= (fn [message] (fn [input] (= message (.-message (deref input)))))]
    (are [x y] (x y)
      (c= 10) (count res)

      (c= (rc/rxnext 2)) (nth res 0)

      (c= (rc/rxnext 4)) (nth res 1)

      (c= (rc/rxnext 2)) (nth res 2)

      (c= (rc/rxnext 4)) (nth res 3)

      rc/rxerror? (nth res 4)
      (m= "Argument must be an integer: []") (nth res 4)

      rc/rxerror? (nth res 5)
      (m= "Argument must be an integer: ()") (nth res 5)

      rc/rxerror? (nth res 6)
      (m= "Argument must be an integer: []") (nth res 6)

      rc/rxerror? (nth res 7)
      (m= "Argument must be an integer: ()") (nth res 7)

      rc/rxerror? (nth res 8)
      (c= (rc/rxerror test-xf-rxwrap-comp-error)) (nth res 8)

      rc/rxerror? (nth res 9)
      (m= "Argument must be an integer: Error: test-xf-rxwrap-comp-error") (nth res 9))))

;; tested in xfcomp tests
(deftest xfwrap)

(deftest xfcomp
  (let [xf (ro/xfcomp (filter odd?) (map inc))
        res (transduce xf conj test-xf-rxwrap-comp-input)]
    (test-xf-rxwrap-comp-output res)))

(deftest pipe
  (ct/async
   done
   (go-let [chan (ro/pipe (async/to-chan test-xf-rxwrap-comp-input)
                          (filter odd?)
                          (map inc))
            res (<! (async/into [] chan))]
     (test-xf-rxwrap-comp-output res)
     (done))))

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
