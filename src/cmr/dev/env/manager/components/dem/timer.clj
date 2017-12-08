(ns cmr.dev.env.manager.components.dem.timer
  "System component for setting up a timing component."
  (:require
    [clojure.core.async :as async]
    [cmr.dev.env.manager.components.dem.messaging :as messaging-component]
    [cmr.dev.env.manager.components.dem.subscribers :as subscribers]
    [cmr.dev.env.manager.config :as config]
    [cmr.dev.env.manager.messaging.core :as messaging]
    [cmr.dev.env.manager.timing :as timing]
    [com.stuartsierra.component :as component]
    [taoensso.timbre :as log]))

(defn batch-subscribe
  "The passed argument `subscribers` is a list of maps with each map having
  `:interval` and `:fn` keys with corresponding values."
  [system subscribers]
  (let [messenger (messaging-component/get-messenger system)]
    (log/trace "Timer batch-subscribe using messenger" messenger)
    (doseq [subscriber subscribers]
      (log/debugf "Subscribing %s to %s ..." (:fn subscriber) (:interval subscriber))
      (messaging/subscribe messenger :timer (:fn subscriber)))))

(defn timer
  [system loop-delay update-fn]
  (let [init-tracker (timing/new-tracker timing/default-intervals)]
    (async/go-loop [tracker init-tracker]
      (async/<! (async/timeout loop-delay))
      (recur (timing/update-tracker timing/default-intervals
                                    tracker
                                    update-fn)))))

(defn- send-message
  [system _times _tracker time-key]
  (messaging-component/publish system :timer {:interval time-key}))

(defrecord Timer [
  builder
  loop-interval
  timer-subscribers]
  component/Lifecycle

  (start [component]
    (log/info "Starting timer component ...")
    (let [cfg (builder :timer)
          component (assoc component :config cfg)]
      (timer component (config/timer-delay component)
                       (partial send-message component))
      (batch-subscribe component (:timer-subscribers component))
      (log/debug "Started timer component.")
      component))

  (stop [component]
    (log/info "Stopping timer component ...")
    (let [messenger (messaging-component/get-messenger component)]
      (log/debug "Stopped timer component.")
      (assoc component :timer-subscribers nil))))

(defn create-component
  "The passed argument `subscribers` is a list of maps with each map having
  `:interval` and `:fn` keys with corresponding values."
  [config-builder-fn loop-interval subscribers]
  (map->Timer
    {:builder config-builder-fn
     :loop-interval loop-interval
     :timer-subscribers subscribers}))
