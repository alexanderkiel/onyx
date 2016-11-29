(ns onyx.messaging.protocols.subscriber
  (:refer-clojure :exclude [key]))

(defprotocol Subscriber
  (info [this])
  (equiv-meta [this sub-info])
  (start [this])
  (stop [this])
  (poll! [this])
  (set-replica-version! [this new-replica-version])
  (set-epoch! [this new-epoch])
  (get-recover [this])
  (offer-ready-reply! [this])
  (completed? [this])
  (blocked? [this])
  (unblock! [this])
  (alive? [this])
  (recovered? [this])
  (set-recover! [this recover*])
  (set-recovered! [this])
  (add-assembler [this])
  (set-heartbeat! [this src-peer-id])
  (offer-heartbeat! [this])
  (src-peers [this])
  (update-sources! [this sources])
  (offer-barrier-status! [this src-peer-id]))
