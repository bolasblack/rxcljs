(ns rxcljs.transformers-test
  (:require-macros
   [rxcljs.core :refer [go go-let <! >!]])
  (:require
   ["lodash.isequal" :as js-equal]
   ["rxjs" :as rx]
   [goog.object :as go]
   [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
   [cljs.core.async :as async]
   [adjutant.core :as ac :include-macros true]
   [rxcljs.core :as rc :refer [RxError] :include-macros true]
   [rxcljs.transformers :as rt :include-macros true]))

(deftest promise?
  (is (not (rt/promise? [])))
  (is (rt/promise? (js/Promise.resolve 1))))

(deftest chan->promise
  (ct/async
   done
   (let [fake-error (js/Error. "fake error")
         resolve-result #(.then
                          %
                          (fn [val] {:type :resolve :val val})
                          (fn [val] {:type :reject :val val}))
         ps [(resolve-result (rt/chan->promise (async/go 1)))
             (resolve-result (rt/chan->promise (async/go (RxError. fake-error))))
             (resolve-result (rt/chan->promise (async/go (RxError. 2))))]
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
   (go-let [fake-error (js/Error. "fake error")
            r1 (async/<! (rt/promise->chan (js/Promise.resolve 1)))
            r2 (async/<! (rt/promise->chan (js/Promise.reject 2)))
            r3 (async/<! (rt/promise->chan (js/Promise.reject fake-error)))
            r4 (async/<! (rt/promise->chan (js/Promise.resolve nil)))]
     (is (= 1 r1))
     (is (= (ex-data (rc/RxError. 2)) (ex-data r2)))
     (is (= (ex-data (rc/RxError. fake-error)) (ex-data r3)))
     (is (= nil r4))
     (done))))

(deftest <p!
  (is (= '(rxcljs.core/<! (rxcljs.transformers/promise->chan promise))
         (macroexpand-1 '(rt/<p! promise)))))



(deftest observable->chan
  (ct/async
   done
   (go-let [fake-error (js/Error. "fake error")
            r1 (async/<! (async/into [] (rt/observable->chan (rx/from #js [1 2 3]))))
            r2 (async/<! (async/into [] (rt/observable->chan (rx/Observable.
                                                              (fn [observer]
                                                                (.next observer 1)
                                                                (.error observer fake-error)
                                                                (.next observer 2))))))]
     (is (= [1 2 3] r1))
     (is (= [1 (rc/rxerror fake-error)] r2))
     (done))))




(defn- is-async-iterator [obj]
  (let [_ (is (fn? (.-next obj)))
        res-fn (go/get obj js/Symbol.asyncIterator)
        _ (is (fn? res-fn))
        res (res-fn)
        _ (is (= res obj))]))

(defn- next-async-iterator [next done value]
  (go-let [_ (is (rt/promise? next))
           res (rt/<p! next)
           _ (is (= done (.-done res)))
           _ (is (js-equal value (.-value res)))]))

(deftest chan->async-iterator
  (ct/async
   done
   (go-let [iterator (rt/chan->async-iterator (async/to-chan (range 3)))
            _ (is-async-iterator iterator)
            _ (async/<! (next-async-iterator (.next iterator) false 0))
            _ (async/<! (next-async-iterator (.next iterator) false 1))
            _ (async/<! (next-async-iterator (.next iterator) false 2))
            _ (async/<! (next-async-iterator (.next iterator) true nil))]
     (done))))

(deftest chan->async-iterator--with-error
  (ct/async
   done
   (go-let [fake-error (js/Error. "test error")

            iterator (rt/chan->async-iterator (go fake-error))
            _ (is-async-iterator iterator)
            next (.next iterator)
            _ (is (rt/promise? next))
            val (try
                  (rt/<p! next)
                  (catch :default e
                    (is false)))
            _ (is fake-error val)
            _ (async/<! (next-async-iterator (.next iterator) true nil))

            iterator (rt/chan->async-iterator (go (throw fake-error)))
            _ (is-async-iterator iterator)
            next (.next iterator)
            _ (is (rt/promise? next))
            val (try
                  (rt/<p! next)
                  (is false)
                  (catch :default e
                    e))
            _ (is fake-error val)
            _ (async/<! (next-async-iterator (.next iterator) true nil))]
     (done))))




(deftest denodify
  (ct/async
   done
   (go-let
     [fake-read-file1
      (rt/denodify
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
      (rt/denodify (fn [path options callback] (callback fake-error)))

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
        (is (ac/error? resp))
        (is (= fake-error resp)))]
     (done))))

(deftest denodify..
  (ct/async
   done
   (do
     (is (= (macroexpand-1 '(rt/denodify.. js/fs.readFile))
            '((rxcljs.transformers/denodify js/fs.readFile))))
     (is (= (macroexpand-1 '(rt/denodify.. js/fs.readFile "foo"))
            '((rxcljs.transformers/denodify js/fs.readFile) "foo")))

     (is (= (macroexpand-1 '(rt/denodify.. ctx -a))
            '((rxcljs.transformers/denodify (.. ctx -a) ctx))))
     (is (= (macroexpand-1 '(rt/denodify.. ctx -a "foo"))
            '((rxcljs.transformers/denodify (.. ctx -a) ctx) "foo")))

     (is (= (macroexpand-1 '(rt/denodify.. ctx -a -b -c))
            '((rxcljs.transformers/denodify (.. ctx -a -b -c) (.. ctx -a -b)))))
     (is (= (macroexpand-1 '(rt/denodify.. ctx -a -b -c "foo"))
            '((rxcljs.transformers/denodify (.. ctx -a -b -c) (.. ctx -a -b)) "foo")))

     (let [obj #js{:a #js{:b nil}}]
       (set! (.-b (.-a obj))
             (fn [path callback]
               (callback nil {:path path
                              :this-bounded? (= (js* "this") (.-a obj))})))
       (go-let
         [resp1 (<! (rt/denodify..
                     (fn [path callback]
                       (callback nil {:path path}))
                     "test path"))
          _ (is (= {:path "test path"} resp1))
          resp2 (<! (rt/denodify.. obj -a -b "test path"))
          _ (is (= {:path "test path" :this-bounded? true} resp2))]
         (done))))))

(deftest <n!
  (ct/async
   done
   (is (= '(rxcljs.core/<! (rxcljs.transformers/denodify.. ctx))
          (macroexpand-1 '(rt/<n! ctx))))
   (is (= '(rxcljs.core/<! (rxcljs.transformers/denodify.. ctx -a "foo"))
          (macroexpand-1 '(rt/<n! ctx -a "foo"))))
   (let [obj #js{:a #js{:b nil}}]
     (set! (.-b (.-a obj))
           (fn [path callback]
             (callback nil {:path path
                            :this-bounded? (= (js* "this") (.-a obj))})))
     (go-let
       [resp1 (rt/<n! (fn [path callback]
                        (callback nil {:path path}))
                      "test path")
        _ (is (= {:path "test path"} resp1))
        resp2 (rt/<n! obj -a -b "test path")
        _ (is (= {:path "test path" :this-bounded? true} resp2))]
       (done)))))




(deftest flat-chan
  (ct/async
   done
   (rc/go-let [fake-error (js/Error. "flat-chan error")
               chan1 (go (go (go 1)))
               chan2 (go 1)
               chan3 1
               chan4 (go (go (throw fake-error)))
               chan5 (go (go fake-error))]
     (is (= 1 (async/<! (rt/flat-chan chan1))))
     (is (= 1 (async/<! (rt/flat-chan chan2))))
     (is (= 1 (async/<! (rt/flat-chan chan3))))
     (is (= (rc/rxerror fake-error) (async/<! (rt/flat-chan chan4))))
     (is (= fake-error (async/<! (rt/flat-chan chan5))))
     (done))))

(deftest <<!
  (let [res (macroexpand-1 '(rt/<<! chan))]
    (is (= '(rxcljs.core/<! (rxcljs.transformers/flat-chan chan))
           res))))
