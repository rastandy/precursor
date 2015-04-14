(ns frontend.rtc
  (:require [cljs.core.async :as async :refer (put! <!)]
            [frontend.rtc.stats :as stats]
            [frontend.utils :as utils])
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)]))

;; How it works:
;;  1. User A hits the record button, which calls getUserMedia and stores the
;;     stream in `stream` and stores `:recording? true` in subscriber-info
;;  2. User B notices that A is recording, and sends a :subscribe-to-recording?
;;     message through the signal channel (a websocket handler)
;;  3. A gets the message, creates a peer connection, adds the stream to it, then
;;     starts sending connection info to B
;;  4. B gets the connection info, creates a peer connection, then sends back some
;;     connection info to A
;;  5. A adds B's connection info to its peer connection and data starts flowing
;;  6. B gets the media stream out of its peer connection, turns it into a URL, and
;;     adds the url to the state, which the view turns into an audio element

;; Things to keep in mind
;;  1. Single peer connection for each pair of peers for each direction of data flow.
;;     Probably only need one for each pair, but 2 is simpler
;;  2. No turn fallback if peers can't connect directly
;;  3. Some APIs from the spec aren't implemented the same way in browsers
;;  4. Switching streams on a conn doesn't work in Chrome, so we create a new conn

;; Still todo
;;  1. Way to turn off recording
;;  2. Mute users
;;  3. Cleanup peer connections when users leave
;;  4. Do something useful with errors (currently reports them)

(def config {:iceServers [{:url "stun:stun.l.google.com:19302"}]})

(def PeerConnection (or js/window.RTCPeerConnection
                        js/window.mozRTCPeerConnection
                        js/window.webkitRTCPeerConnection))

(def RTCIceCandidate (or js/window.RTCIceCandidate
                         js/window.mozRTCIceCandidate))

(def RTCSessionDescription (or js/window.RTCSessionDescription
                               js/window.mozRTCSessionDescription))

(def supports-rtc? (boolean PeerConnection))

;; map of conn-id (:producer, :consumer, :stream-id) to map with keys :conn, :producer, :consumer, and :stream-id
(defonce conns (atom {}))

;; single media stream from microphone
(defonce stream (atom nil))

(defn ^:export inspect-conns []
  (clj->js (mapv (comp :conn last) @conns)))

(defn ^:export inspect-stream []
  @stream)

(defn maybe-close [conn]
  (when-not (= "closed" (.-iceConnectionState conn))
    (.close conn)))

(defn new-peer-conn [extra-servers]
  (PeerConnection. (clj->js (update-in config [:iceServers] concat extra-servers))))

(defn handle-negotiation [conn signal-fn]
  (.createOffer conn
                (fn [offer]
                  (.setLocalDescription conn
                                        offer
                                        #(signal-fn {:sdp (js/JSON.stringify (.-localDescription conn))})
                                        #(utils/report-error "error setting local description in negotiation" %)))
                #(utils/report-error "Error handling negotitation" %)))

(defn handle-ice-candidate [conn signal-fn event]
  (when-let [c (.-candidate event)]
    (signal-fn {:candidate (js/JSON.stringify c)})))

(defn should-update-stats? [conn id]
  (get @conns id))

(defn update-stats [conn id]
  (if-let [selector (some-> (or (first (.getLocalStreams conn))
                                (first (.getRemoteStreams conn)))
                      (.getAudioTracks)
                      first)]
    (stats/get-stats conn
                     selector
                     (fn [resp]
                       (let [stats-data (stats/report->map resp)]
                         (swap! conns utils/update-when-in [id] (fn [data]
                                                                  (assoc data
                                                                         :stats stats-data
                                                                         :previous-stats (:stats data)))))
                       (when (should-update-stats? conn id)
                         (js/window.setTimeout #(update-stats conn id)
                                               1000)))
                     #(utils/report-error "error gathering stats" %))
    ;; stream may not be attached yet, don't let that kill the stats loop
    (when (should-update-stats? conn id)
      (js/window.setTimeout #(update-stats conn id)
                            1000))))

(defn setup-get-stats [conn id]
  (update-stats conn id))

(defn handle-connection [conn id signal-fn]
  (if (= "closed" (.-iceConnectionState conn))
    (signal-fn {:close-connection true})
    (doseq [candidate-str (get-in @conns [id :candidates])]
      (.addIceCandidate
       conn
       (RTCIceCandidate. (js/JSON.parse candidate-str))
       #(do
          (swap! conns update-in [id :candidates] disj candidate-str)
          (utils/mlog "successfully set ice candidate that failed"))
       #(utils/report-error "error setting ice candidate that failed" %)))))

(defn setup-listeners [conn id signal-fn]
  (doseq [event-name ["connecting" "track" "negotiationneeded"
                      "signalingstatechange" "iceconnectionstatechange"
                      "icegatheringstatechange" "icecandidate" "datachannel"
                      "isolationchange" "identityresult" "peeridentity"
                      "idpassertionerror" "idpvalidationerror"]]
    (.addEventListener conn event-name (fn [e] (utils/mlog event-name e))))
  (.addEventListener conn "icecandidate" #(handle-ice-candidate conn signal-fn %))
  (.addEventListener conn "negotiationneeded" #(handle-negotiation conn signal-fn))
  (.addEventListener conn "iceconnectionstatechange" #(handle-connection conn id signal-fn))
  conn)

;; Handle navigator.mediaStreams.getUserMedia, which doesn't seem to exist in the wild
;; https://developer.mozilla.org/en-US/docs/Web/API/MediaDevices/getUserMedia
(def getUserMedia (or js/navigator.mozGetUserMedia
                      js/navigator.webkitGetUserMedia))

(defonce audio-ctx (js/window.AudioContext.))

(defn get-user-media [config success error]
  (.call getUserMedia js/navigator (clj->js config) success error))

(defn watch-volume [stream ch]
  (let [bin-count 16
        analyser (.createAnalyser audio-ctx)
        _ (set! (.-fftSize analyser) (* 2 bin-count))
        _ (set! (.-smoothingTimeConstant analyser) 0.2)
        source (.createMediaStreamSource audio-ctx stream)
        data-array (js/Uint8Array. bin-count)]

    (.connect source analyser)

    (go-loop []
      (async/<! (async/timeout 50))
      (when-not (.-ended stream)
        (.getByteFrequencyData analyser data-array)
        (put! ch [:media-stream-volume {:stream-id (.-id stream)
                                        :volume (/ (reduce (fn [acc i]
                                                             (+ acc (* 100 (/ (aget data-array i)
                                                                              256))))
                                                           0 (range bin-count))
                                                   bin-count)}])
        (recur)))))

;; http://www.w3.org/TR/mediacapture-streams/#h-event-summary
(defn add-stream-watcher [stream ch]
  ;; TODO: figure out which of these events is real
  (.addEventListener stream "inactive" #(put! ch [:media-stream-stopped {:stream-id (.-id stream)}]))
  (.addEventListener stream "ended" #(put! ch [:media-stream-stopped {:stream-id (.-id stream)}]))
  (watch-volume stream ch))

(defn cleanup-conns [k v]
  (doseq [cleanup-key (filter #(= v (get % k)) (keys @conns))]
    (some-> @conns
      (get cleanup-key)
      :conn
      maybe-close)
    (swap! conns dissoc cleanup-key)))

(defn setup-stream [ch]
  (get-user-media {:audio true}
                  (fn [s]
                    (when-let [old @stream]
                      (.stop old)
                      (cleanup-conns :stream-id (.-id old)))
                    (reset! stream s)
                    (add-stream-watcher s ch)
                    (put! ch [:media-stream-started {:stream-id (.-id s)}]))
                  #(put! ch [:media-stream-failed {:error (.-name %)}])))

(defn end-stream [stream-id]
  (when-let [old @stream]
    (swap! stream #(if (and % (= (.-id %) stream-id))
                     nil
                     %))
    (when (= stream-id (.-id old))
      (.stop old)
      (cleanup-conns :stream-id (.-id old)))))

(defn add-stream [conn stream]
  ;; spec says this should be addMediaTrack
  (.addStream conn stream))

(defn conn-id [stream-id producer consumer]
  {:stream-id stream-id :consumer consumer :producer producer})

(defn workaround-firefox-negotiation-bug
  "Firefox won't fire negotiationneeded after adding a stream:
   https://bugzilla.mozilla.org/show_bug.cgi?id=1071643.
   Fixed, but not widely released"
  [conn signal-fn]
  (let [negotiation-timer (js/window.setTimeout #(handle-negotiation conn signal-fn) 10)]
    (.addEventListener conn "negotiationneeded" #(js/window.clearTimeout negotiation-timer))))

(defn setup-producer [{:keys [signal-fn stream producer consumer ice-servers]}]
  (let [conn (new-peer-conn ice-servers)
        id (conn-id (.-id stream) producer consumer)]
    (swap! conns assoc id {:conn conn :consumer consumer :producer producer :stream-id (.-id stream)})
    (setup-listeners conn id signal-fn)
    (setup-get-stats conn id)
    (workaround-firefox-negotiation-bug conn signal-fn)
    (add-stream conn stream)))

(defn get-or-create-peer-conn [signal-fn stream-id producer consumer ice-servers]
  (let [id (conn-id stream-id producer consumer)
        conns-before @conns
        new-conns (swap! conns update-in [id] #(or % {:conn (setup-listeners (new-peer-conn ice-servers) id signal-fn)
                                                      :consumer consumer
                                                      :producer producer
                                                      :stream-id stream-id}))
        conn (get-in new-conns [id :conn])]
    ;; racey with multiple threads, but 1. single-thread and 2. multiple watchers won't break anything
    (when-not (get-in conns-before [id :conn])
      (setup-get-stats conn id))
    conn))

(defn get-peer-conn [stream-id producer consumer]
  (get-in @conns [(conn-id stream-id producer consumer) :conn]))

(defn handle-sdp [{:keys [signal-fn sdp-str stream-id producer consumer comms ice-servers]}]
  (let [desc (RTCSessionDescription. (js/JSON.parse sdp-str))]
    (if (= "offer" (.-type desc))
      (let [conn (get-or-create-peer-conn signal-fn stream-id producer consumer ice-servers)
            ch (async/chan)]
        (go
          (try
            (.setRemoteDescription conn desc #(put! ch :desc) #(put! ch {:error %}))
            (if-let [error (:error (<! ch))]
              (put! (:errors comms) [:rtc-error {:error error :msg "error setting remote description"}])
              (.createAnswer conn #(put! ch {:answer %}) #(put! ch {:error %})))
            (let [resp (<! ch)]
              (if-let [error (:error resp)]
                (put! (:errors comms) [:rtc-error {:error error :msg "error creating answer"}])
                (.setLocalDescription conn (:answer resp) #(put! ch :desc) #(put! ch {:error %}))))
            (if-let [error (:error (<! ch))]
              (put! (:errors comms) [:rtc-error {:error error :msg "error setting local description"}])
              (do
                (signal-fn {:sdp (js/JSON.stringify (.-localDescription conn))})
                (put! (:controls comms) [:remote-media-stream-ready {:stream-url (js/window.URL.createObjectURL (first (.getRemoteStreams conn)))
                                                                  :producer producer}])))
            (catch js/Error e
              (put! (:errors comms) [:rtc-error {:error e :msg "error in handle-sdp for offer"}]))
            (finally
              (async/close! ch)))))
      (let [conn (get-peer-conn stream-id producer consumer)]
        (.setRemoteDescription conn desc
                               #(utils/mlog "successfully set remote description")
                               #(utils/report-error "error setting remote description in handle-sdp" %))))))

(defn add-candidate [signal-fn candidate-str stream-id producer consumer ice-servers]
  (let [conn (get-or-create-peer-conn signal-fn stream-id producer consumer ice-servers)]
    (.addIceCandidate
     conn
     (RTCIceCandidate. (js/JSON.parse candidate-str))
     #(utils/mlog "successfully set ice candidate")
     #(do (utils/mlog "error setting ice candidate, will try it again on connection change")
          (swap! conns update-in [(conn-id stream-id producer consumer) :candidates]
                 (fnil conj #{}) candidate-str)))))

;; signal-fn takes a map of data, e.g. {:candidate "candidate-string"}
(defn handle-signal [{:keys [send-msg producer consumer stream-id comms ice-servers] :as data}]
  (let [signal-fn (fn [d]
                    (let [data (merge d {:producer producer :consumer consumer :stream-id stream-id})]
                      (utils/mlog "sending signal" data)
                      (send-msg data)))]
    (cond (:candidate data)
          (add-candidate signal-fn (:candidate data) stream-id producer consumer ice-servers)

          (:sdp data)
          (handle-sdp {:sdp-str (:sdp data) :stream-id stream-id :producer producer :consumer consumer
                       :signal-fn signal-fn :comms comms :ice-servers ice-servers})

          (:subscribe-to-recording data)
          (if-let [stream @stream]
            (if (= (.-id stream) (get-in data [:subscribe-to-recording :stream-id]))
              (setup-producer {:signal-fn signal-fn :stream stream :ice-servers ice-servers :consumer consumer :producer producer})
              (utils/report-error "subscribe to recording of different or outdated stream"))
            (utils/report-error "Subscribe to recording without stream"))

          (:close-connection data)
          (let [id (conn-id stream-id producer consumer)]
            (some-> @conns
              (get id)
              :conn
              maybe-close)
            (swap! conns dissoc id)))))

(defn ^:export inspect-stats []
  (clj->js (stats/gather-stats conns stream)))
