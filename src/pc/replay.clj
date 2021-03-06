(ns pc.replay
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer])
  (:import java.util.UUID))

(defn- ->datom
  [[e a v tx added]]
  {:e e :a a :v v :tx tx :added added})

(defn touched-eids [tx-id]
  (d/q '{:find [[?e ...]]
         :in [?log ?tx]
         :where [[(tx-data ?log ?tx) [[?e]]]]}
       (d/log (pcd/conn)) tx-id))

(defn tx-data [tx-id]
  (->> (d/tx-range (d/log (pcd/conn)) tx-id (inc tx-id))
    first
    :data
    (map ->datom)
    set))

(defn get-document-tx-ids
  "Returns array of tx-ids sorted by db/txInstant"
  [db doc]
  (map first
       (sort-by second
                (d/q '{:find [?t ?tx]
                       :in [$ ?doc-id]
                       :where [[?t :transaction/document ?doc-id]
                               [?t :transaction/broadcast]
                               [?t :db/txInstant ?tx]]}
                     db (:db/id doc)))))

(defn reproduce-transaction [db tx-id]
  (let [tx (d/entity db tx-id)]
    {:tx-data (tx-data tx-id)
     :tx tx
     :db-after db}))

(defn get-document-transactions
  "Returns a lazy sequence of transactions for a document in order of db/txInstant."
  [db doc]
  (map (partial reproduce-transaction db)
       (get-document-tx-ids db doc)))

(defn replace-frontend-ids [db doc-id txes]
  (let [a (d/entid db :frontend/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :v (UUID. doc-id (web-peer/client-part (:v tx))))
             tx))
         txes)))

(defn doc-id-attr->ref-attr [db {:keys [e v] :as d}]
  (let [ent (d/entity db e)]
    (cond (:layer/type ent) :layer/document
          (:chat/body ent)  :chat/document
          (:layer/name ent) :layer/document
          (= :layer (:entity/type ent)) :layer/document
          (:layer/end-x ent) :layer/document
          :else (throw (Exception. (format "couldn't find attr for %s" d))))))

(defn replace-document-ids [db txes]
  (let [a (d/entid db :document/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :a (doc-id-attr->ref-attr (d/as-of db (if (:added tx)
                                                            (:tx tx)
                                                            (dec (:tx tx))))
                                              tx))
             tx))
         txes)))

(defn copy-transactions [db doc new-doc & {:keys [sleep-ms remove-tx]
                                           :or {sleep-ms 1000}}]
  (let [conn (pcd/conn)
        txes (->> (get-document-transactions db doc)
               (remove (fn [tx] (contains? remove-tx (:db/id (:tx tx)))))
               (map (fn [tx]
                      (update-in tx
                                 [:tx-data]
                                 (fn [tx-data]
                                   (->> tx-data
                                     (remove #(= (:e %) (:db/id (:tx tx))))
                                     (map #(if (= (:v %) (:db/id doc))
                                             (assoc % :v (:db/id new-doc))
                                             %))
                                     (replace-frontend-ids db (:db/id new-doc))
                                     (replace-document-ids db)))))))
        eid-translations (-> (reduce (fn [acc {:keys [tx-data]}]
                                       (set/union acc (set (map :e tx-data))))
                                     #{} txes)
                           (disj (:db/id doc))
                           (zipmap (repeatedly #(d/tempid :db.part/user)))
                           (assoc (:db/id doc) (:db/id new-doc)))
        reverse-eid-translations (set/map-invert eid-translations)
        new-eids (reduce (fn [new-eid-trans tx]
                           (if-not (seq (:tx-data tx))
                             new-eid-trans
                             (let [txid (d/tempid :db.part/tx)
                                   tempids (map #(get eid-translations %)
                                                (remove #(get new-eid-trans %)
                                                        (map :e (:tx-data tx))))
                                   new-tx @(d/transact conn (conj (map #(-> %
                                                                          (update-in [:e] (fn [e] (or (get new-eid-trans e)
                                                                                                      (get eid-translations e))))
                                                                          pcd/datom->transaction)
                                                                       (:tx-data tx))
                                                                  (merge
                                                                   {:db/id txid
                                                                    :transaction/document (:db/id new-doc)}
                                                                   (when (:transaction/broadcast (:tx tx))
                                                                     {:transaction/broadcast true}))))]
                               (merge new-eid-trans
                                      (zipmap (map #(get reverse-eid-translations %) tempids)
                                              (map #(d/resolve-tempid (:db-after new-tx) (:tempids new-tx) %) tempids))))))
                         {} txes)]
    @(d/transact conn (map (fn [e]
                             [:db/add e :frontend/id (UUID. (:db/id new-doc) e)])
                           (remove #(:frontend/id (d/entity db %))
                                   (vals new-eids))))
    new-doc))

(defn needs-frontend-id? [db eid]
  (let [ent (d/entity db eid)]
    (and (:dummy ent)
         (not (:frontend/id ent)))))

(defn ensure-deleted-frontend-ids [db doc]
  (let [txids (get-document-tx-ids db doc)
        eids (reduce (fn [acc tx]
                       (set/union acc (set (filter #(and (not (contains? acc %))
                                                         (or (needs-frontend-id? (d/as-of db tx) %)
                                                             (needs-frontend-id? (d/as-of db (dec tx)) %)))
                                                   (touched-eids tx)))))
                     #{} txids)]
    @(d/transact (pcd/conn) (map (fn [e] [:db/add e :frontend/id (UUID. (:db/id doc) e)])
                                 eids))))

(defn ensure-transaction-broadcast [db doc]
  (let [txids (d/q '{:find [[?t ...]]
                     :in [$ ?doc-id]
                     :where [[?t :transaction/document ?doc-id]
                             [?t :db/txInstant]]}
                   db (:db/id doc))]
    (d/transact (pcd/conn)
                (map (fn [e]
                       [:db/add e :transaction/broadcast true])
                     (filter #(let [ent (d/entity db %)]
                                (and (not (:transaction/broadcast ent))
                                     (:session/uuid ent)))
                             txids)))))

#_(defn clear-cache [db doc]
    (doseq [tx-id (get-document-tx-ids db doc)]
      (pc.cache/delete (str "pc.http.sente/frontend-tx-data-" tx-id)))
    (clojure.core.memoize/memo-clear! pc.http.sente/memo-frontend-tx-data))
