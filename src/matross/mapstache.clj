(ns matross.mapstache
  (:import clojure.lang.Atom
           clojure.lang.Associative
           clojure.lang.IFn
           clojure.lang.ILookup
           clojure.lang.IPersistentMap
           clojure.lang.Seqable
           clojure.lang.MapEntry
           clojure.lang.IPersistentCollection
           clojure.lang.IPersistentVector
           clojure.lang.SeqIterator))

(declare mapstache)

(defprotocol IRender (render [this str data]))

(defn no-template
  "Mark a Var as not templated to prevent mapstache from templating any values within it"
  [m]
  (vary-meta m assoc :mapstache-no-template true))

(defn- no-template? [m] (:mapstache-no-template (meta m)))

(defn- circular-path-message [p]
  (str "Circular key lookup: "
       (->> p
            (map (fn [path] (map name path)))
            (map (fn [path] (clojure.string/join "." path)))
            (clojure.string/join " -> "))))


(deftype Mapstache [^matross.mapstache.IRender renderer
                    ^IPersistentMap value
                    ^IPersistentVector cursor
                    ^Atom lookups
                    root]

  Associative
  (containsKey [this k] (contains? (get-in value cursor) k))
  (entryAt [this k]
    (if (.containsKey this k)
      (MapEntry. k (get-in value (conj cursor k)))))

  ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [this k not-found]
    (let [lookup-key (conj cursor k)
          v (get-in value lookup-key not-found)
          root (or root this)]

      (if (no-template? v)
        v
        (cond
         (instance? IPersistentMap v)
         (mapstache renderer value lookup-key lookups root)

         (instance? IPersistentCollection v)
         (let [new-ms (mapstache renderer value lookup-key lookups root)]
           (map-indexed
            (fn [idx _] (. new-ms valAt idx)) v))

         (instance? String v)
         (if (= (.indexOf @lookups lookup-key) -1)
           (try
             (swap! lookups conj lookup-key)
             (render renderer v root)
             (finally (swap! lookups pop)))
           (let [message (circular-path-message (conj @lookups lookup-key))]
             (throw (IllegalArgumentException. message))))

         :else v))))

  IFn
  (invoke [this k] (.valAt this k))
  (invoke [this k not-found] (.valAt this k not-found))
  (toString [this] (str [value cursor lookups]))

  Seqable
  (seq [this]
    (map (fn [k] (MapEntry. k (.valAt this k))) (keys (get-in value cursor))))

  IPersistentCollection
  (count [this] (count (get-in value cursor)))
  (empty [this] (empty? (get-in value cursor)))
  (equiv [this o]
    (let [my-value (get-in value cursor)]
      (if (instance? Mapstache o)
        (= my-value (get-in (.value o) (.cursor o)))
        (= my-value o))))
  (cons [this o]
    (let [new-value (if (empty? cursor)
                      (conj value o)
                      (update-in value cursor conj o))]
      (mapstache renderer new-value cursor lookups root)))

  IPersistentMap
  (assoc [this k v]
    (mapstache renderer (assoc-in value (conj cursor k) v) cursor lookups root))
  (assocEx [this k v]
    (if (contains? k (get-in value cursor))
      (throw (IllegalArgumentException. (str "Already contains key: " k)))
      (.assoc this k v)))

  (without [this k]
    (let [new-value (if (empty? cursor)
                      (dissoc value k)
                      (update-in value cursor dissoc k))]
      (mapstache renderer new-value cursor lookups root)))

  Iterable
  (iterator [this] (SeqIterator. (.seq this))))

(defn mapstache
  ([renderer value]
     (mapstache renderer value [] (atom []) nil))
  ([renderer value cursor lookups root]
     (Mapstache. renderer value cursor lookups root)))
