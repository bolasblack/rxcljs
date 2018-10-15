(ns rxcljs.core-test
  (:refer-clojure :exclude [into])
  (:require-macros
   [rxcljs.core :refer [go]])
  (:require
   [pjstadig.humane-test-output]
   ["lodash.isequal" :as js-equal]
   ["rxjs" :as rx]
   [goog.object :as go]
   [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
   [cljs.core.async :as async :refer [>! put!]]
   [utils.core :as uc :include-macros true]
   [rxcljs.core :as ua :refer [<! RxNext RxError] :include-macros true]))

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
        (>! chan (cljs.core/into [1] args))
        (async/<! (async/timeout duration))
        (>! chan (cljs.core/into [2] args))
        (async/<! (async/timeout duration))
        (>! chan (cljs.core/into [3] args))
        (async/close! chan))
    chan))

(deftest wait-multiple-chan
  (ct/async
   done
   (ua/go-let
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
   (ua/go-let [e (ex-info "foo" {})
               ch (async/chan)]
     (is (= (ua/rxnext 1)
            (async/<! (ua/go 1))))
     (is (= (ua/rxerror e)
            (async/<! (ua/go (throw e)))))
     (is (= (ua/rxerror e)
            (async/<! (ua/go (ua/rxerror e)))))
     (async/put! ch (ua/rxerror e))
     (async/<!
      (ua/go
        (try
          (<! ch)
          (is false)
          (catch js/Error err
            (is (= e err))))))
     (done))))

(deftest go-test-with-non-standard-error
  (ct/async
   done
   (ua/go-let [e [123 nil]
               ch (async/chan)]
     (put! ch e)
     (is (= e (<! (ua/go (<! ch)))))
     (put! ch (ua/rxerror e))
     (ua/go
       (try
         (<! ch)
         (is false)
         (catch :default err
           (is (= e err)))))
     (done))))




(defn read-both [ch-a ch-b]
  (ua/go-let [a (<! ch-a)
              b (<! ch-b)]
    (is false)
    [a b]))

(deftest <!-test-1
  (ct/async
   done
   (ua/go-let [e (ex-info "foo" {})
               ch-a (go (throw e))
               ch-b (go 1)
               res (async/<! (read-both ch-a ch-b))]
     (is (= (ua/rxerror e) res))
     (done))))




(deftest chan?
  (is (not (ua/chan? [])))
  (is (ua/chan? (async/chan))))




(deftest promise?
  (is (not (ua/promise? [])))
  (is (ua/promise? (js/Promise.resolve 1))))

(deftest chan->promise
  (ct/async
   done
   (let [fake-error (js/Error. "fake error")
         resolve-result #(.then
                          %
                          (fn [val] {:type :resolve :val val})
                          (fn [val] {:type :reject :val val}))
         ps [(resolve-result (ua/chan->promise (async/go (RxNext. 1))))
             (resolve-result (ua/chan->promise (async/go (RxError. fake-error))))
             (resolve-result (ua/chan->promise (async/go (RxError. 2))))]
         final-promise (js/Promise.all (clj->js ps))]
     (.then
      final-promise
      (fn [rs]
        (is (= [{:type :resolve :val 1}
                {:type :reject :val fake-error}
                {:type :reject :val 2}]
               (js->clj rs)))
        (done))
      (fn [err]
        (is false)
        (done))))))

(deftest promise->chan
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "fake error")
               r1 (async/<! (ua/promise->chan (js/Promise.resolve 1)))
               r2 (async/<! (ua/promise->chan (js/Promise.reject 2)))
               r3 (async/<! (ua/promise->chan (js/Promise.reject fake-error)))
               r4 (async/<! (ua/promise->chan (js/Promise.resolve nil)))]
     (is (= (ua/RxNext. 1) r1))
     (is (= (ex-data (ua/RxError. 2)) (ex-data r2)))
     (is (= (ex-data (ua/RxError. fake-error)) (ex-data r3)))
     (is (= nil r4))
     (done))))

(deftest <p!
  (is (= '(rxcljs.core/<! (rxcljs.core/promise->chan promise))
         (macroexpand-1 '(ua/<p! promise)))))



(deftest observable->chan
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "fake error")
               r1 (async/<! (async/into [] (ua/observable->chan (rx/from #js [1 2 3]))))
               r2 (async/<! (async/into [] (ua/observable->chan (rx/Observable.
                                                                 (fn [observer]
                                                                   (.next observer 1)
                                                                   (.error observer fake-error)
                                                                   (.next observer 2))))))]
     (is (= [(ua/rxnext 1) (ua/rxnext 2) (ua/rxnext 3)] r1))
     (is (= [(ua/rxnext 1) (ua/rxerror fake-error)] r2))
     (done))))




(defn- is-async-iterator [obj]
  (let [_ (is (fn? (.-next obj)))
        res-fn (go/get obj js/Symbol.asyncIterator)
        _ (is (fn? res-fn))
        res (res-fn)
        _ (is (= res obj))]))

(defn- next-async-iterator [next done value]
  (ua/go-let [_ (is (ua/promise? next))
              res (ua/<p! next)
              _ (is (= done (.-done res)))
              _ (is (js-equal value (.-value res)))]))

(deftest chan->async-iterator
  (ct/async
   done
   (ua/go-let [iterator (ua/chan->async-iterator (async/to-chan (range 3)))
               _ (is-async-iterator iterator)
               _ (async/<! (next-async-iterator (.next iterator) false 0))
               _ (async/<! (next-async-iterator (.next iterator) false 1))
               _ (async/<! (next-async-iterator (.next iterator) false 2))
               _ (async/<! (next-async-iterator (.next iterator) true nil))]
     (done))))

(deftest chan->async-iterator--with-error
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "test error")

               iterator (ua/chan->async-iterator (ua/go fake-error))
               _ (is-async-iterator iterator)
               next (.next iterator)
               _ (is (ua/promise? next))
               val (try
                     (ua/<p! next)
                     (catch :default e
                       (is false)))
               _ (is fake-error val)
               _ (async/<! (next-async-iterator (.next iterator) true nil))

               iterator (ua/chan->async-iterator (ua/go (throw fake-error)))
               _ (is-async-iterator iterator)
               next (.next iterator)
               _ (is (ua/promise? next))
               val (try
                     (ua/<p! next)
                     (is false)
                     (catch :default e
                       e))
               _ (is fake-error val)
               _ (async/<! (next-async-iterator (.next iterator) true nil))]
     (done))))




(deftest flat-chan
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "flat-chan error")
               chan1 (go (go (go 1)))
               chan2 (go 1)
               chan3 1
               chan4 (go (go (throw fake-error)))
               chan5 (go (go fake-error))]
     (is (= (ua/rxnext 1) (async/<! (ua/flat-chan chan1))))
     (is (= (ua/rxnext 1) (async/<! (ua/flat-chan chan2))))
     (is (= (ua/rxnext 1) (async/<! (ua/flat-chan chan3))))
     (is (= (ua/rxerror fake-error) (async/<! (ua/flat-chan chan4))))
     (is (= (ua/rxnext fake-error) (async/<! (ua/flat-chan chan5))))
     (done))))

(deftest <<!
  (let [res (macroexpand-1 '(ua/<<! chan))]
    (is (= '(rxcljs.core/<! (rxcljs.core/flat-chan chan))
           res))))




(deftest limit-map
  (ct/async
   done
   (ua/go-let [limit-chan (async/chan 5)
               last-job-info (volatile! nil)
               last-job-result (volatile! nil)
               job-ids (range 10)
               map-chan (ua/limit-map
                         #(do (vreset! last-job-info %)
                              (* 2 %))
                         job-ids
                         limit-chan)]

     (ua/go-loop []
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




(deftest denodify
  (ct/async
   done
   (ua/go-let
     [fake-read-file1
      (ua/denodify
       (js* "function readFile(path, options, callback) {
const keys = Object.keys(this)
callback(null, {
  path: path,
  options: options,
  'this-bounded?': keys.length === 1 && keys[0] === 'bounded' && this.bounded,
})}")
       #js {:bounded true})

      _
      (do
        (is (= "denodified_readFile" (.-name fake-read-file1)))
        (is (= 2 (.-length fake-read-file1))))

      resp
      (js->clj (<! (fake-read-file1 "/file/path.text" nil))
               :keywordize-keys true)

      _
      (do
        (is (map? resp))
        (is (= {:path "/file/path.text"
                :options nil
                :this-bounded? true}
               resp)))

      fake-error
      (js/Error. "test error")

      fake-read-file2
      (ua/denodify (fn [path options callback] (callback fake-error)))

      _
      (do
        (is (= "denodified_fn" (.-name fake-read-file2)))
        (is (= 2 (.-length fake-read-file2))))

      resp
      (try
        (<! (fake-read-file2 "/file/path.text" nil))
        (is false)
        (catch :default err
          err))

      _
      (do
        (is (uc/error? resp))
        (is (= fake-error resp)))]
     (done))))

(deftest denodify..
  (ct/async
   done
   (do
     (is (= (macroexpand-1 '(ua/denodify.. js/fs.readFile))
            '((rxcljs.core/denodify js/fs.readFile))))
     (is (= (macroexpand-1 '(ua/denodify.. js/fs.readFile "foo"))
            '((rxcljs.core/denodify js/fs.readFile) "foo")))

     (is (= (macroexpand-1 '(ua/denodify.. ctx -a))
            '((rxcljs.core/denodify (.. ctx -a) ctx))))
     (is (= (macroexpand-1 '(ua/denodify.. ctx -a "foo"))
            '((rxcljs.core/denodify (.. ctx -a) ctx) "foo")))

     (is (= (macroexpand-1 '(ua/denodify.. ctx -a -b -c))
            '((rxcljs.core/denodify (.. ctx -a -b -c) (.. ctx -a -b)))))
     (is (= (macroexpand-1 '(ua/denodify.. ctx -a -b -c "foo"))
            '((rxcljs.core/denodify (.. ctx -a -b -c) (.. ctx -a -b)) "foo")))

     (let [obj #js{:a #js{:b nil}}]
       (set! (.-b (.-a obj))
             (fn [path callback]
               (callback nil {:path path
                              :this-bounded? (= (js* "this") (.-a obj))})))
       (ua/go-let
         [resp1 (<! (ua/denodify..
                     (fn [path callback]
                       (callback nil {:path path}))
                     "test path"))
          _ (is (= {:path "test path"} resp1))
          resp2 (<! (ua/denodify.. obj -a -b "test path"))
          _ (is (= {:path "test path" :this-bounded? true} resp2))]
         (done))))))
