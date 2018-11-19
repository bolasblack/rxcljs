(ns rxcljs.operators-test
  (:require-macros
   [rxcljs.core :refer [go go-let go-loop <! >!]])
  (:require
   [cljs.test :as ct :refer-macros [deftest testing is are] :include-macros true]
   [cljs.core.async :as async]
   [rxcljs.core :as rc]
   [rxcljs.operators :as ro]))

(def test-xf-rxwrap-comp-error
  (js/Error. "test-xf-rxwrap-comp-error"))

(def test-xf-rxwrap-comp-input
  [1 2 3
   [] (list)
   (rc/rxerror test-xf-rxwrap-comp-error) test-xf-rxwrap-comp-error])

(defn test-xf-rxwrap-comp-output [res]
  (let [c= (fn [v1] (fn [v2] (= v1 v2)))
        m= (fn [message] (fn [input] (= message (.-message (deref input)))))]
    (are [x y] (x y)
      (c= 6) (count res)

      (c= 2) (nth res 0)

      (c= 4) (nth res 1)

      rc/rxerror? (nth res 2)
      (m= "Argument must be an integer: []") (nth res 2)

      rc/rxerror? (nth res 3)
      (m= "Argument must be an integer: ()") (nth res 3)

      rc/rxerror? (nth res 4)
      (c= (rc/rxerror test-xf-rxwrap-comp-error)) (nth res 4)

      rc/rxerror? (nth res 5)
      (m= "Argument must be an integer: Error: test-xf-rxwrap-comp-error") (nth res 5))))

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

(deftest concurrency-normal-case
  (ct/async
   done
   (go-let [f-called-with (volatile! [])
            f-completed (volatile! nil)
            brake-chans (repeatedly 5 async/chan)
            dst-chan (ro/concurrency
                      (range 5)
                      2
                      #(go (vswap! f-called-with conj %)
                           (<! (nth brake-chans %))
                           (vreset! f-completed %)
                           %))]

     (<! (async/timeout 0))
     (is (= [0 1] @f-called-with) "check @f-called-with step 1")
     (is (nil? @f-completed) "check @f-completed step 1")
     (is (nil? (async/poll! dst-chan)) "poll! dst-chan step 1")

     (>! (nth brake-chans 0) 1)
     (<! (async/timeout 0))
     (is (= [0 1 2] @f-called-with) "check @f-called-with step 2")
     (is (= 0 @f-completed) "check @f-completed step 2")
     (is (= 0 (async/poll! dst-chan)) "1st poll! dst-chan step 2")
     (is (nil? (async/poll! dst-chan)) "2nd poll! dst-chan step 2")

     (>! (nth brake-chans 2) 1)
     (<! (async/timeout 0))
     (is (= [0 1 2 3] @f-called-with) "check @f-called-with step 3")
     (is (= 2 @f-completed) "check @f-completed step 3")
     (is (= 2 (async/poll! dst-chan)) "1st poll! dst-chan step 3")
     (is (nil? (async/poll! dst-chan)) "2nd poll! dst-chan step 3")

     (>! (nth brake-chans 3) 1)
     (>! (nth brake-chans 4) 1)
     (<! (async/timeout 0))
     (is (= [0 1 2 3 4] @f-called-with) "check @f-called-with step 4")
     (is (= 4 @f-completed) "check @f-completed step 4")
     (is (= 3 (async/poll! dst-chan)) "1st poll! dst-chan step 4")
     (is (= 4 (async/poll! dst-chan)) "2nd poll! dst-chan step 4")
     (is (nil? (async/poll! dst-chan)) "3rd poll! dst-chan step 4")

     (<! (async/timeout 0))
     (is (= [0 1 2 3 4] @f-called-with) "check @f-called-with step 5")
     (is (= 4 @f-completed) "check @f-completed step 5")
     (is (nil? (async/poll! dst-chan)) "1st poll! dst-chan step 5")
     (is (not (rc/closed? dst-chan)) "check dst-chan closed step 5")

     (>! (nth brake-chans 1) 1)
     (<! (async/timeout 0))
     (is (= [0 1 2 3 4] @f-called-with) "check @f-called-with step 6")
     (is (= 1 @f-completed) "check @f-completed step 6")
     (is (= 1 (async/poll! dst-chan)) "1st poll! dst-chan step 6")
     (is (nil? (async/poll! dst-chan)) "2nd poll! dst-chan step 6")
     (is (rc/closed? dst-chan) "check dst-chan closed step 6")

     (done))))

(deftest concurrency-with-error
  (ct/async
   done
   (go-let [fake-error1 (js/Error. "concurrency-with-error fake error 1")
            fake-error2 (js/Error. "concurrency-with-error fake error 2")
            processed-values (volatile! [])
            dst-chan (ro/concurrency
                      [0 1 (rc/rxerror fake-error1) 3 4 5]
                      2
                      #(go (vswap! processed-values conj %)
                           (if (= 4 %)
                             (throw fake-error2)
                             %)))
            results (<! (async/into [] dst-chan))
            [r1 r2 r3 r4 r5 r6] results]

     (is (= 5 (count @processed-values)))
     (is (= [0 1 3 4 5] @processed-values))

     (is (= 6 (count results)))
     (is (= 0 r1))
     (is (= 1 r2))
     (is (= 3 r4))
     (is (= 5 r6))
     (is (rc/rxerror? r3))
     (is (identical? fake-error1 @r3))
     (is (rc/rxerror? r5))
     (is (identical? fake-error2 @r5))

     (done))))




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

(deftest test-map
  (ct/async
   done
   (go-let
     [start (js/Date.now)
      chan (ro/map
            #(conj %& (- (js/Date.now) start))
            [(create-chan 100 :a1 :a2)
             (create-chan 200 :b1 :b2)])

      d1 (<! chan)
      _ (is (close-to? 200 (first d1)))
      _ (is (= (next d1)
               '([1 :a1 :a2]
                 [1 :b1 :b2])))

      d2 (<! chan)
      _ (is (close-to? 400 (first d2)))
      _ (is (= (next d2)
               '([2 :a1 :a2]
                 [2 :b1 :b2])))

      d3 (<! chan)
      _ (is (close-to? 600 (first d3)))
      _ (is (= (next d3)
               '([3 :a1 :a2]
                 [3 :b1 :b2])))]

     (done))))

(deftest test-map-returns-channel
  (ct/async
   done
   (go-let []
     (is (= [[1 2] 8 [6 7]]
            (<! (async/into
                 []
                 (ro/map
                  #(if (= 4 %2)
                     (async/to-chan [8 9])
                     (vector %1 %2))
                  [(async/to-chan [1 3 6])
                   (async/to-chan [2 4 7])])))))
     (done))))

(deftest test-map-error-handle
  (ct/async
   done
   (go-let [fake-error (js/Error. "fake error")]
     (is (= [[1 2] (rc/rxerror fake-error)]
            (<! (async/into
                 []
                 (ro/map
                  vector
                  [(async/to-chan [1 (rc/rxerror fake-error) 4])
                   (async/to-chan [2 3 5])])))))
     (done))))
