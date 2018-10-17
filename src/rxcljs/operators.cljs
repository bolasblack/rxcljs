(ns rxcljs.operators
  (:require-macros
   [rxcljs.core :refer [go go-let go-loop <! >!]])
  (:require
   [clojure.core.async :as async]
   [rxcljs.core :as rc]))

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
