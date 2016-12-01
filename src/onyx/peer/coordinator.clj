(ns onyx.peer.coordinator
  (:require [com.stuartsierra.component :as component]
            [onyx.schema :as os]
            [clojure.core.async :refer [alts!! <!! >!! <! >! poll! timeout promise-chan dropping-buffer chan close! thread]]
            [taoensso.timbre :refer [info error warn trace fatal]]
            [schema.core :as s]
            [onyx.monitoring.measurements :refer [emit-latency emit-latency-value]]
            [com.stuartsierra.component :as component]
            [onyx.messaging.protocols.messenger :as m]
            [onyx.messaging.protocols.publisher :as pub]
            [onyx.messaging.messenger-state :as ms]
            [onyx.extensions :as extensions]
            [onyx.log.replica]
            [onyx.static.default-vals :refer [defaults arg-or-default]]))

(defn required-type-checkpoints [replica job-id task-type]
  (let [replica-key (case task-type 
                      :input :input-tasks
                      :output :output-tasks
                      :state :state-tasks)
        tasks (get-in replica [replica-key job-id])] 
    (->> (get-in replica [:task-slot-ids job-id])
         (filter (fn [[task-id _]] (get tasks task-id)))
         (mapcat (fn [[task-id peer->slot]]
                   (map (fn [[_ slot-id]]
                          [task-id slot-id task-type])
                        peer->slot)))
         set)))

;; Required checkpoint needs all checkpoints for the previous actual peers
(defn required-checkpoints [replica job-id]
  (clojure.set/union (required-type-checkpoints replica job-id :input)
                     (required-type-checkpoints replica job-id :state)
                     (required-type-checkpoints replica job-id :output)))

(defn max-completed-checkpoints [log replica job-id]
  (let [required (required-checkpoints replica job-id)] 
    (or (extensions/latest-full-checkpoint log job-id required)
        :beginning)))

(defn input-publications [replica peer-id job-id]
  (let [allocations (get-in replica [:allocations job-id])
        input-tasks (get-in replica [:input-tasks job-id])]
    (->> input-tasks
         (mapcat (fn [task]
                   (map (fn [id] 
                          (let [site (get-in replica [:peer-sites id])
                                colocated (->> (get allocations task)
                                               (filter (fn [peer-id]
                                                         (= site (get-in replica [:peer-sites peer-id]))))
                                               set)]
                            (assert (not (empty? colocated)))
                            {:src-peer-id [:coordinator peer-id]
                             :dst-task-id [job-id task]
                             :dst-peer-ids colocated
                             ;; all input tasks can receive coordinator barriers on the same slot
                             ;; TODO, remove this stuff?
                             :slot-id -1
                             :site site}))
                        (get allocations task))))
         (set))))

(defn offer-heartbeats
  [{:keys [messenger] :as state}]
  (run! pub/offer-heartbeat! (m/publishers messenger))
  (assoc state :last-heartbeat-time (System/currentTimeMillis)))

(defn offer-barriers
  [{:keys [messenger rem-barriers barrier-opts offering?] :as state}]
  (assert messenger)
  (if offering? 
    (loop [pubs rem-barriers]
      (if-not (empty? pubs)
        (let [pub (first pubs)
              ret (m/offer-barrier messenger pub barrier-opts)]
          (if (pos? ret)
            (recur (rest pubs))
            (assoc state :rem-barriers pubs)))
        (-> state 
            (assoc :last-barrier-time (System/currentTimeMillis))
            (update :messenger m/unblock-subscriber!)
            (assoc :checkpoint-version nil)
            (assoc :offering? false)
            (assoc :rem-barriers nil))))
    state))

(defn emit-reallocation-barrier 
  [{:keys [log job-id peer-id messenger prev-replica] :as state} new-replica]
  (let [replica-version (get-in new-replica [:allocation-version job-id])
        new-messenger (-> messenger 
                          (m/update-publishers (input-publications new-replica peer-id job-id))
                          (m/set-replica-version! replica-version))
        checkpoint-version (max-completed-checkpoints log new-replica job-id)
        new-messenger (m/next-epoch! new-messenger)]
    (assoc state 
           :last-barrier-time (System/currentTimeMillis)
           :offering? true
           :barrier-opts {:recover checkpoint-version}
           :rem-barriers (m/publishers new-messenger)
           :prev-replica new-replica
           :messenger new-messenger)))

(defn periodic-barrier [{:keys [prev-replica job-id messenger offering?] :as state}]
  (if offering?
    ;; No op because hasn't finished emitting last barrier, wait again
    state
    (let [messenger (m/next-epoch! messenger)] 
      (assoc state 
             :offering? true
             :barrier-opts {}
             :rem-barriers (m/publishers messenger)
             :messenger messenger))))

(defn coordinator-action [{:keys [messenger peer-id job-id] :as state} action-type new-replica]
  (info "Coordinator action" action-type)
  (assert 
   (if (#{:reallocation-barrier} action-type)
     (some #{job-id} (:jobs new-replica))
     true))
  ;(assert (= peer-id (get-in new-replica [:coordinators job-id])) [peer-id (get-in new-replica [:coordinators job-id])])
  (case action-type 
    :offer-barriers (offer-barriers state)
    :offer-heartbeats (offer-heartbeats state)
    :shutdown (assoc state :messenger (component/stop messenger))
    :periodic-barrier (periodic-barrier state)
    :reallocation-barrier (emit-reallocation-barrier state new-replica)))

(defn start-coordinator! [{:keys [peer-config allocation-ch shutdown-ch] :as state}]
  (thread
   (try
    (let [;; FIXME: allow in job data
          barrier-period-ms (arg-or-default :onyx.peer/coordinator-barrier-period-ms peer-config)
          ; TODO
          ;snapshot-every-n (arg-or-default :onyx.peer/coordinator-snapshot-every-n-barriers peer-config)
          heartbeat-ms (arg-or-default :onyx.peer/heartbeat-ms peer-config)] 
      (loop [state (-> state 
                       (assoc :last-barrier-time (System/currentTimeMillis))
                       (assoc :last-heartbeat-time (System/currentTimeMillis)))]
        (if (poll! shutdown-ch)
          (coordinator-action state :shutdown (:prev-replica state))
          (let [replica (poll! allocation-ch)]
            (cond replica
                  ;; Set up reallocation barriers. Will be sent on next recur through :offer-barriers
                  (recur (coordinator-action state :reallocation-barrier replica))

                  (:offering? state)
                  ;; Continue offering barriers until success
                  (recur (coordinator-action state :offer-barriers (:prev-replica state))) 

                  (< (+ (:last-heartbeat-time state) heartbeat-ms) 
                     (System/currentTimeMillis))
                  ;; Immediately offer heartbeats
                  (recur (coordinator-action state :offer-heartbeats (:prev-replica state)))

                  (< (+ (:last-barrier-time state) barrier-period-ms) 
                     (System/currentTimeMillis))
                  ;; Setup barriers, will be sent on next recur through :offer-barriers
                  (recur (coordinator-action state :periodic-barrier (:prev-replica state)))

                  :else
                  (do
                   ;; Remove magic number
                   (Thread/sleep 5)
                   (recur state)))))))
    (catch Throwable e
      (>!! (:group-ch state) [:restart-vpeer (:peer-id state)])
      (fatal e "Error in coordinator")))))

(defprotocol Coordinator
  (start [this])
  (stop [this])
  (started? [this])
  (send-reallocation-barrier? [this old-replica new-replica])
  (next-state [this old-replica new-replica]))

(defn next-replica [{:keys [allocation-ch] :as coordinator} replica]
  (when (started? coordinator) 
    (>!! allocation-ch replica))
  coordinator)

(defn start-messenger [messenger replica job-id]
  (-> messenger 
      component/start
      ;; Probably bad to have to default to -1, though it will get updated immediately
      (m/set-replica-version! (get-in replica [:allocation-version job-id] -1))))

(defn stop-coordinator! [{:keys [shutdown-ch allocation-ch]}]
  (when shutdown-ch
    (>!! shutdown-ch true)
    (close! shutdown-ch))
  (when allocation-ch 
    (close! allocation-ch)))

(defrecord PeerCoordinator [log messenger-group peer-config peer-id job-id messenger
                            group-ch allocation-ch shutdown-ch coordinator-thread]
  Coordinator
  (start [this] 
    (info "Piggybacking coordinator on peer:" peer-id)
    (let [initial-replica (onyx.log.replica/starting-replica peer-config)
          messenger (-> (m/build-messenger peer-config messenger-group [:coordinator peer-id])
                        (start-messenger initial-replica job-id)) 
          allocation-ch (chan (dropping-buffer 1))
          shutdown-ch (promise-chan)]
      (assoc this 
             :started? true
             :allocation-ch allocation-ch
             :shutdown-ch shutdown-ch
             :messenger messenger
             :coordinator-thread (start-coordinator! 
                                   {:log log
                                    :peer-config peer-config 
                                    :messenger messenger 
                                    :prev-replica initial-replica 
                                    :job-id job-id
                                    :peer-id peer-id 
                                    :group-ch group-ch
                                    :allocation-ch allocation-ch 
                                    :shutdown-ch shutdown-ch}))))
  (started? [this]
    (true? (:started? this)))
  (stop [this]
    (info "Stopping coordinator on:" peer-id)
    (stop-coordinator! this)
    ;; TODO blocking retrieve value from coordinator thread so that we can wait for full shutdown
    (info "Coordinator stopped.")
    (assoc this :allocation-ch nil :started? false :shutdown-ch nil :coordinator-thread nil))
  (send-reallocation-barrier? [this old-replica new-replica]
    (and (some #{job-id} (:jobs new-replica))
         (not= (get-in old-replica [:allocation-version job-id])
               (get-in new-replica [:allocation-version job-id]))))
  (next-state [this old-replica new-replica]
    (let [started? (= (get-in old-replica [:coordinators job-id]) 
                      peer-id)
          start? (= (get-in new-replica [:coordinators job-id]) 
                    peer-id)]
      (cond-> this
        (and (not started?) start?)
        (start)

        (and started? (not start?))
        (stop)

        (send-reallocation-barrier? this old-replica new-replica)
        (next-replica new-replica)))))

(defn new-peer-coordinator [log messenger-group peer-config peer-id job-id group-ch]
  (map->PeerCoordinator {:log log
                         :group-ch group-ch
                         :messenger-group messenger-group 
                         :peer-config peer-config 
                         :peer-id peer-id 
                         :job-id job-id}))
