# Snoop (Alpha)
<img width="260"
     align="right"
     src="https://user-images.githubusercontent.com/41270840/121264336-e5c9bb80-c8ae-11eb-8466-427b0636b3d0.png">

Function instrumention for Clojure(Script) using [malli](https://github.com/metosin/malli/) schemas and a custom defn wrapper.

Inspired by [Guardrails](https://github.com/fulcrologic/guardrails) and [malli-instrument](https://github.com/setzer22/malli-instrument).

## Rationale

I wanted a way to use malli schemas to check the validity of the inputs and outputs of functions. Instrumentation is a conventient way to spot errors using real-world data and it does not require writing tests upfront. malli-instrument and [aave](https://github.com/teknql/aave) had limitations that made them unsuitable for my needs.

I attempted to modify malli-instrument to be ClojureScript-compatible. However, I found that `clojure.spec`-like instrumentation (which works on regular `defn`s) can be inconvenient with hot code reloading and evaluating functions on the fly. Thus, I took the approach of using a `>defn` macro, which has the following benefits:

* Makes it more convenient to specify the schema
  * You do not have to define the function symbol twice (once for the function, again for the schema using `m/=>`)
  * In multi-arity functions, schemas can be colocated with each arity
* Easy to quickly disable instrumentation on individual functions
* No special linter required (can be linted as `defn`)

## Installation

deps.edn:
```
{com.crypticbutter/snoop {:git/url "https://github.com/crypticbutter/snoop.git" 
                          :sha "..."}}
```

Then either:
- Create a `snoop.edn` file. Specify the `-Dsnoop.enabled` JVM option when launching a REPL.
- [Only available with ClojureScript] Provide the compiler options: `{:external-config {:crypticbutter.snoop {}}}`

Snoop is disabled by default and will throw an exception if enabled in a CLJS production build.

## Using the `>defn` macro

| **Prerequisite:** | [understand malli's function schemas](https://github.com/metosin/malli#function-schemas)
| --- | ---

```clojure
(require '[crypticbutter.snoop :refer [>defn]])
```

The `>defn` macro is backwards compatible with `defn` (you can swap out one symbol with the other without breaking any code).

There are multiple ways of specifying your schema(s).

### Using malli's function schema registry:

```clojure
(require '[malli.core :as m])

(m/=> add [:=> [:cat int? int?] int?])
(>defn add [x y] ...)
```

### Inside the function body:
```clojure
(>defn add [x y]
  [:=> [:cat int? int?] int?]
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
The second schema above uses a similar notation to [ghostwheel](https://github.com/gnl/ghostwheel). The `=>` can be substituted with `:=>`, `'=>` or `:ret`

To outstrument a 0-parameter function, you could use `[=> int?]`

### Inside the pre-post map:
```clojure
(>defn add [x y]
  {:=> [[:cat int? int?] int?]}
  ...)
```

### Multiple arities with mixed notations:
```clojure
(>defn add 
  ([x]
    [int? int? => int?]
    ...)
  ([x y]
    {:=> [:=> [:cat int? int?] int?]}
    ...))
```

### No schema

Schemas are optional. `>defn` works fine without the schema (acts as a regular `defn` without the instrumentation):
```clojure
(>defn add [x y]
   ;; advanced maths
  ...)
```

## Configuration

At runtime, you are able to modify the `crypticbutter.snoop.config/*config` atom.

| Key | Default | Description |
| --- | --- | --- 
| `:on-instrument-fail` | | Function to call when the input is not valid. Receives single argument. |
| `:on-outstrument-fail` | | Function to call when the output is not valid. Receives single argument. |
| `:malli-opts`         | {} | Given to `m/explain` which is used for validation. |
| `:instrument?`        | true | Whether to enable validation on a function's arguments. |
| `:outstrument?`       | true | Whether to enable validation on a function's return value. |
| `:whitelist-by-default` | true | Determines whether validation is allowed on functions by default. If set to false, functions must be whitelisted in order for validation to occur. |
| `:blacklist-ns` | #{} | Set of namespace symbols for which in/outstrumentation should be disallowed. |
| `:whitelist-ns` | #{} | Similar to above but allows validation in the namespaces. Only useful if `:whitelist-by-default` is false. |
| `:whitelist-fn` | {} | Maps namespace symbols to sets of function symbols whose validation should be allowed. Overrides the namespace rules above. |
| `:blacklist-fn` | {} | Similar to above but disallows validation. |

## Improvements to be made

- [ ] Be able to use multi-arity function schemas when using `m/=>` eg `[:function [:=> ...`
- [ ] In `>defn`, combine schemas for each arity into a single schema and call `m/=>` at runtime to register a schema passed via the pre-post map or body.
- [ ] Provide facilities to allow valiation to be done in a different thread in CLJS.
- [ ] Option for asynchronous checking in Clojure JVM

I will probably only work on new features as I need them. Please report any issues to run into whilst using this library.
