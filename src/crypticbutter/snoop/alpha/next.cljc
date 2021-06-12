(ns crypticbutter.snoop.alpha.next)

(comment
  #_:clj-kondo/ignore
  ;; has the benefit of colocation
  (defn fun
    "Does this"
    {x int?
     y int?
     & more
     :& [:* string?]
     :=> [:tuple int? int?]}
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "Does this"
    {x int?
     y int?
     & (more [:* string?])
     :=> [:tuple int? int?]}
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "Does this"
    {x int?, y int? & (more [:* string?])
     :=> [:tuple int? int?]}
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "does something"
    [(x int?) (y int?) & (more [:* string?])]  ;; optional schemas per param
    :=> [:tuple int? int?]
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "does something"
    [(x int?) (y int?) & (more [:* string?])]
    [:=> [:tuple int? int?]]
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "does something"
    [(x int?) (y int?) & (more [:* string?])]
    {:=> [:tuple int? int?]} ;; I like this - in prepost map
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  (defn fun
    "does something"
    [(x int?) (y int?) & (more [:* string?])
     :=> [:tuple int? int?]]
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  ;; Looks as though schema is the function validating the arg
  ;; Syntax more similar to other widespread languages (type before param)
  (defn fun
    "does something"
    [(int? x) (int? y) & ([:* string?] more)]
    {:=> [:tuple int? int?]}
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  ;; similar to pre-post
  (defn fun
    "does something"
    [x y & more]
    {:in [int? int? [:* string?]]
     :=> [:tuple int? int?]}
    ;; or
    {:in {x int? y int? more [:* string?]} ;; duplication of symbols, but makes optional schemas easier
     :=> [:tuple int? int?]}
    (into [x (* x y)] more))

  #_:clj-kondo/ignore
  ;; does not require use of macro at all
  ;; just provide funcitons to use in regular prepost maps
  ;; not only will these return a boolean, but they could provide more detailed error messages
  ;; could have async versions that always return true
  ;; more verbose
  (defn fun
    "does something"
    [x y & more]
    {:pre [(snoop/pre x int?)
           (snoop/pre y int?)
           (snoop/pre more [:* string?])]
     :post [(snoop/post % [:tuple int? int?])]}
    (into [x (* x y)] more))

;;
  )
