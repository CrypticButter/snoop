# Snoop (Alpha)
<img width="260"
     align="right"
     src="https://user-images.githubusercontent.com/41270840/121264336-e5c9bb80-c8ae-11eb-8466-427b0636b3d0.png">


[![Clojars Project](https://img.shields.io/clojars/v/com.crypticbutter/snoop.svg)](https://clojars.org/com.crypticbutter/snoop)

Function instrumention for Clojure(Script) using [malli](https://github.com/metosin/malli/)
schemas and a custom defn wrapper.

Inspired by [Guardrails](https://github.com/fulcrologic/guardrails) and
[malli-instrument](https://github.com/setzer22/malli-instrument).

## Rationale

I wanted a way to use malli schemas to check the validity of the inputs and outputs
of functions. Instrumentation is a conventient way to spot errors using real-world
data and it does not require writing tests upfront. malli-instrument and [aave](https://github.com/teknql/aave)
had limitations that made them unsuitable for my needs.

I attempted to modify malli-instrument to be ClojureScript-compatible. However,
I found that `clojure.spec`-like instrumentation (which works on regular `defn`s)
can be inconvenient with hot code reloading and evaluating functions on the fly.
Thus, I took the approach of using a `>defn` macro, which has the following benefits:

* Makes it more convenient to specify the schema
  * You do not have to define the function symbol twice (once for the function,
  again for the schema using `m/=>`)
  * In multi-arity functions, schemas can be colocated with each arity
* Easy to quickly disable instrumentation on individual functions
* No special linter required (can be linted as `defn`)

![example snoop](https://user-images.githubusercontent.com/41270840/121600548-88637500-ca3c-11eb-918c-7464a6db0887.png)

## Installation

[![Clojars Project](https://img.shields.io/clojars/v/com.crypticbutter/snoop.svg)](https://clojars.org/com.crypticbutter/snoop)

deps.edn:

```clojure
com.crypticbutter/snoop {:mvn/version "21-228-alpha"}
metosin/malli {:mvn/version "LATEST"}
```

(Also see [the changelog](./CHANGELOG.org))

Then either:
- Create a `snoop.edn` file with a map in it. Specify the `-Dsnoop.enabled` JVM option
when launching a REPL. E.g `clj -J-Dsnoop.enabled` or `:jvm-opts ["-Dsnoop.enabled"]`
in deps.edn
- [Only available with ClojureScript] Provide the compiler options: `{:external-config {:crypticbutter.snoop {}}}`
  - Example for a shadow-cljs build: `:your-build {:dev {:compiler-options {:external-config {:crypticbutter.snoop {}}}} ...}`

Snoop is disabled by default and will throw an exception if enabled in a CLJS
production build.

If there are any problems installing & using, please let me know.

## Using the `>defn` macro

| **Prerequisite:** | [understand malli's function schemas](https://github.com/metosin/malli#function-schemas)
| --- | ---

```clojure
(require '[crypticbutter.snoop :refer [>defn]])
```

The `>defn` macro is optionally backwards compatible with `defn` (you can swap out one
symbol with the other without breaking any code). This makes it more feasible to
combine multiple defn wrappers (also see the [:defn-sym option](#Compile-time-config)).

Enclosed, you will find a clj-kondo config export, for your linting convenience.
See ([exporting and importing clj-kondo configs](https://github.com/clj-kondo/clj-kondo/blob/master/doc/config.md#exporting-and-importing-configuration)).

There are multiple ways of specifying your schema(s).

### Using malli's function schema registry:

```clojure
(require '[malli.core :as m])

(m/=> add [:=> [:cat int? int?] int?])
(>defn add [x y] ...)
```

The schema specified with `m/=>` will be ignored if any schema is specified within the function body.

### Inside the function body:
```clojure
(>defn add [x [y z]] ;; You can still use destructuring
  [:=> [:cat int? [:tuple int? int?]] int?]
  ...)
```

### More convenient notations that work when using `>defn`:
```clojure
;; Require `=>` solely to prevent unresolved symbol linting errors
(require '[crypticbutter.snoop :refer [>defn =>]])

(>defn add [x y]
  ;; Either:
  [[:cat int? int?] int?]
  ;; Or:
  [int? int? => int?]
  ...)
```
The second schema above uses a similar notation to [ghostwheel](https://github.com/gnl/ghostwheel).
The `=>` can be substituted with `:=>`, `'=>` or `:ret`

To outstrument a 0-parameter function, you could use `[=> int?]` — this means
there will be no input validation.

### Inside the prepost map:
```clojure
(>defn add [x y]
  {:=> [[:cat int? int?] int?]}
  ...)
```

The main motivation for this option is that it could make combining defn wrappers
easier by allowing you to forward the schema via the prepost map. Requires that you
are able to set the `defn` symbol used by the top-level macro.

### Multiple arities and variadic functions

You can mix and match notations.

```clojure
(>defn add
  ([x]
    [int? => int?]
    ...)
  ([x y]
    {:=> [:=> [:cat int? int?] int?]}
    ...)
  ([x y & zs]
    ;; Either
    [int? int? [:+ int?] => int?]
    ;; Or
    [[:cat int? int? [:+ int?]] int?]
    ...))
```

You could also use `m/=>`.

```clojure
(m/=> add [:function
           [:=> [:cat int?] int?]
           [:=> [:cat int? int?] int?]
           [:=> [:cat int? int? [:+ int?]] int?]])
(>defn add
  ([x] ...)
  ([x y] ...)
  ([x y & zs] ...))
```

### No schema

Schemas are optional. `>defn` works fine without the schema (acts as a regular
`defn` without the instrumentation):
```clojure
(>defn add [x y]
   ;; advanced maths
  ...)
```

### Inline Schema

You can choose to depart from the standard `defn` pattern and specify your schemas
right alongside your function parameters. You will need a custom linter, so you may
find the included clj-kondo config export useful ([exporting and importing clj-kondo configs](https://github.com/clj-kondo/clj-kondo/blob/master/doc/config.md#exporting-and-importing-configuration)).

```clojure
(>defn wow
  [(mickey string?)
   (mouse) ;; this argument is not validated
   ({:keys [fun]} MySchema) ;; Destructuring still available
   house ;; list brackets are optional for schemaless params
   & (melon [:* int?])]
  ...)
```

The disadvantage of this syntax is that you are limited to considering each argument
individually; You cannot validate the relationship between arguments.

Note that the inline schemas are used for validation *in addition* to any schema specified
before the function body (or any schema defined with `m/=>`). For example, this will always throw an error:

```clojure
(>defn doom
  [(x int?)]
  [string? => :any]
  ;; x cannot be int and string at the same time
  ...)
```

However, this also means that you can use an additional schema vector to specify
the return schema:

```clojure
(>defn melon [(x int?) (y int?) (z melon?)]
  [=> string?]
  ...)
```

As with the other methods, this works with multiple arities:

```clojure
(>defn add
  ([(x int?)]
    [=> int?]
    ...)
  ([(x int?) (y int?)]
    {:=> [=> int?]} ;; return value is int, specified in prepost map
    ...)
  ([(x int?) (y int?) & (zs [:* int?])]
    ;; with no output schema
    ...))
```

### Support for Keyword Argument Functions

Treat the keyword arguments as a single map argument (as if it were a fixed-arity function).
If no keyword arguments are passed, an empty map (instead of nil) will be used for validation
(so you do not have to wrap `:map` with `:maybe`).

```clojure
(>defn f [a & {:keys [b c]}]
  [int? [:map
         [:b int?]
         [:c {:optional true} int?]]
   => int?]
   ...)
```

## Configuration

There are two main global configurations and they can be overrided for individual functions:

### Runtime config

At runtime, you are able to modify the `crypticbutter.snoop/*config` atom,
which affects the behaviour of instrumented functions.

| Key                     | Default                                 | Description                                                                                                                                        |
| ---                     | ---                                     | ---                                                                                                                                                |
| `:on-instrument-fail`   |                                         | Function to call when the input is not valid. Receives single argument.                                                                            |
| `:on-outstrument-fail`  |                                         | Function to call when the output is not valid. Receives single argument.                                                                           |
| `:log-error-fn`         | #?(:clj println :cljs js/console.error) | Used to log errors at runtime. Must be variadic.                                                                                                   |
| `:malli-opts`           | {}                                      | Given to `m/explain` which is used for validation.                                                                                                 |
| `:instrument?`          | true                                    | Whether to enable validation on a function's arguments.                                                                                            |
| `:outstrument?`         | true                                    | Whether to enable validation on a function's return value.                                                                                         |
| `:whitelist-by-default` | true                                    | Determines whether validation is allowed on functions by default. If set to false, functions must be whitelisted in order for validation to occur. |
| `:blacklist-ns`         | #{}                                     | Set of namespace symbols for which in/outstrumentation should be disallowed.                                                                       |
| `:whitelist-ns`         | #{}                                     | Similar to above but allows validation in the namespaces. Only useful if `:whitelist-by-default` is false.                                         |
| `:whitelist-fn`         | {}                                      | Maps namespace symbols to sets of function symbols whose validation should be allowed. Overrides the namespace rules above.                        |
| `:blacklist-fn`         | {}                                      | Similar to above but disallows validation.                                                                                                         |

### Compile-time config

You can also modify the config used by the macros. This can be done in `snoop.edn`
or via the CLJS compiler options (see [Installation](#Installation)).

| Key         | Default                          | Description                                                                                                                                                                                         |
| ---         | ---                              | ---                                                                                                                                                                                                 |
| :enabled?   | `true` (only if config provided) | Whether to augment the function body with instrumentation features. This is the master switch, and should not be true in a production build.                                                        |
| :defn-sym   | `clojure.core/defn`              | The *symbol* to use for `defn`. This allows you to combine `defn` wrappers as long as their structures are compatible with the core `defn` macro (you can forward data via metadata or prepost maps). |
| :log-fn-sym | `clojure.core/println`           | The *symbol* used to resolve the function used for logging messages during compile-time. |

### Per-function config

You can provide config overrides as metadata (including via an `attr-map`).

- `::snoop/macro-config` gets merged on top of the compile-time config. Whatever you
provide here, it must be possible to `eval` it as compile-time (so all the appropriate
vars must be bound and you cannot pass in locals).

- `::snoop/config-atom` will be used within the function instead of `snoop/*config`. In
ClojureScript, this will be attached to the metadata of the function object because
[var metadata does not get evaluated](https://clojurescript.org/about/differences#_special_forms).

```clojure
(require '[crypticbutter.snoop :as snoop :refer [>defn]])

(def special-compiletime-config {:enabled? true
                                 :defn-sym 'some.magic/>defn})

(def special-runtime-config (atom {:malli-opts {...} :on-instrument-fail ...}))

(>defn fun-function
  {::snoop/macro-config special-compiletime-config
   ::snoop/config-atom special-runtime-config}
  []
  ['=> string?]
  "🍉")
```

## Improvements to be made

- [ ] In `>defn`, combine schemas for each arity into a single schema and call `m/=>`. Would be useful for malli.dev static schema checking facilities.
at runtime to register a schema passed via the prepost map or body.
- [ ] Provide facilities to allow valiation to be done in a different thread in CLJS.
- [ ] Option for asynchronous checking in Clojure JVM

I will probably only work on new features as I need them. That said, please report any
issues you run into whilst using this library.

---

<img align="right" src="https://user-images.githubusercontent.com/41270840/121725121-bf8b6200-cae0-11eb-8d25-4fd0807f4b8e.png">

# Contributing

See [development](./docs/development.adoc)

And ensure the tests pass: [testing](./docs/testing.org)

I'll publish more details in the future.

# License

Copyright © 2021 Luis Thiam-Nye and contributors.

Distributed under Eclipse Public License 2.0, see [LICENSE](./LICENSE).
