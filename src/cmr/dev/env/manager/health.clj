(ns cmr.dev.env.manager.health
  (:require
    [cheshire.core :as json]
    [clj-http.client :as httpc]
    [cmr.dev.env.manager.process.docker :as docker]
    [cmr.transmit.config :as transmit]
    [taoensso.timbre :as log]))

(def http-default-opts
  {:headers {:echo-token transmit/mock-echo-system-token}
   :accept :json
   :throw-exceptions false})

(defn http-health-resource
  [service-key]
  (str (transmit/application-public-root-url service-key)
       "health"))

(defn http-parse-response
  [response]
  (log/trace "HTTP health check response:" response)
  {:status (:status response)
   :details (json/parse-string (:body response) true)})

;; XXX remove system args here once the health component is created
(defn http
  ([system service-key]
    (http system service-key http-default-opts))
  ([system service-key opts]
    (http system service-key (http-health-resource service-key) opts))
  ([_system _service-key health-check-url opts]
    (http-parse-response
      (httpc/get health-check-url opts))))
