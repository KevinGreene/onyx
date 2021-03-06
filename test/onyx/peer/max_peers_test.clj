(ns onyx.peer.max-peers-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is testing]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config]]
            [onyx.api]))

(def n-messages 15000)

(def in-chan (chan (inc n-messages)))

(def out-chan (chan (sliding-buffer (inc n-messages))))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(deftest max-peers-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/id id)
        peer-config (assoc (:peer-config config) :onyx/id id)
        env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)
        batch-size 40
        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :inc
                  :onyx/fn :onyx.peer.max-peers-test/my-inc
                  :onyx/type :function
                  :onyx/max-peers 1
                  :onyx/batch-size batch-size}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Writes segments to a core.async channel"}]
        workflow [[:in :inc] [:inc :out]]
        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls :onyx.peer.max-peers-test/in-calls}
                    {:lifecycle/task :in
                     :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.peer.max-peers-test/out-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.plugin.core-async/writer-calls}]

        _ (doseq [n (range n-messages)]
            (>!! in-chan {:n n}))
        _ (>!! in-chan :done)

        v-peers (onyx.api/start-peers 8 peer-group)
        _ (onyx.api/submit-job
            peer-config
            {:catalog catalog :workflow workflow
             :lifecycles lifecycles
             :task-scheduler :onyx.task-scheduler/balanced})
        ;;;;;;;;;;;;;;;;;;;; TODO: Verify only 3 peers were actually used ;;;;;;;;;;;;;;;;;;;;;;;;;;;;
        results (take-segments! out-chan)]

    (doseq [v-peer v-peers]
      (onyx.api/shutdown-peer v-peer))

    (let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
      (is (= expected (set (butlast results))))
      (is (= :done (last results))))

    (onyx.api/shutdown-peer-group peer-group)
    (onyx.api/shutdown-env env)))


