# Benchmarks

Runnable benchmarks for the speed claims made about this library, so you do not
have to take them on trust.

They use [JMH](https://openjdk.org/projects/code-tools/jmh/), which handles
warmup, JIT effects, dead-code elimination and fork isolation. That matters
here: the operations being measured take about two nanoseconds, which is well
inside the range where a hand-written timing loop measures its own overhead
instead of the thing under test.

## Running them

```bash
mvn clean install -DskipTests
cd benchmark
java -jar target/benchmarks.jar
```

That runs everything with default settings and takes several minutes. To go
faster, or to run one group:

```bash
# just the read benchmarks
java -jar target/benchmarks.jar ReadBenchmark

# a quick, less rigorous pass - 1 fork, 3 warmup and 3 measurement iterations
java -jar target/benchmarks.jar -f 1 -wi 3 -i 3

# one benchmark, and write the results to a file
java -jar target/benchmarks.jar ReadBenchmark.configGetInt -rff results.txt
```

The benchmarks do not run during a normal build. `mvn install` only compiles
them, which is enough to keep them from rotting as the library changes.

> Run these on an idle machine. A laptop that is thermally throttling, or busy
> with a browser, can move these numbers by more than the differences being
> measured.

### Is the warmup enough?

Yes, and it was checked rather than assumed. Running the read benchmarks with
four times the warmup (10 iterations of 2s instead of 5 of 1s) moves nothing:

| benchmark | 5 x 1s warmup | 10 x 2s warmup |
|---|---|---|
| `aField` | 0.357 ± 0.047 | 0.344 ± 0.014 |
| `configGetInt` | 2.242 ± 0.036 | 2.245 ± 0.036 |
| `hashMapGet` | 2.103 ± 0.025 | 2.081 ± 0.048 |

The per-iteration output (`-v EXTRA`) shows why: the first warmup iteration
comes in around 2.33 ns and it has settled to ~2.22 by the third or fourth.
Everything after that is machine noise rather than the JIT still working.

If you change a benchmark, it is worth re-checking this - a benchmark doing
more work per invocation may need longer to reach steady state.

## What is measured

### `ReadBenchmark`

Reading one property, against three reference points:

- **`aField`** - reading a plain field. The floor; nothing can be faster.
- **`hashMapGet`** - a `HashMap<String, Integer>`, the obvious thing to reach
  for instead of a config library.
- **`propertiesGetAndParse`** - `Properties.getProperty` followed by
  `Integer.parseInt`, which is what an application does with no config library
  at all.

Then `configGetInt` and friends, plus two benchmarks that answer "should I copy
a config value into a local before a hot loop?" - `tenConfigReads` against
`tenCachedReads`.

### `StartupBenchmark`

Building the whole config: reading the file, loading the sources, parsing and
validating every value. This happens once per application start. Runs with 10,
100 and 1000 properties, and separately with values carrying escape sequences,
since unescaping is the most expensive part of parsing a value.

## Results, and what they actually show

Measured on an Apple M-series laptop, JDK 26, 2 forks, 5 warmup and 5
measurement iterations. **Your numbers will differ; the relationships between
them are the point.**

```
Benchmark                            Mode  Cnt   Score   Error  Units
ReadBenchmark.aField                 avgt   10   0.357 ± 0.047  ns/op
ReadBenchmark.hashMapGet             avgt   10   2.103 ± 0.025  ns/op
ReadBenchmark.configGetString        avgt   10   2.168 ± 0.003  ns/op
ReadBenchmark.configGetInt           avgt   10   2.242 ± 0.036  ns/op
ReadBenchmark.configGetIntList       avgt   10   2.243 ± 0.032  ns/op
ReadBenchmark.configGetLong          avgt   10   2.248 ± 0.027  ns/op
ReadBenchmark.configGetBoolean       avgt   10   2.274 ± 0.027  ns/op
ReadBenchmark.configGetDouble        avgt   10   2.326 ± 0.068  ns/op
ReadBenchmark.propertiesGetAndParse  avgt   10   4.767 ± 0.009  ns/op
ReadBenchmark.tenCachedReads         avgt   10   2.334 ± 0.026  ns/op
ReadBenchmark.tenConfigReads         avgt   10  22.454 ± 0.044  ns/op
```

Reading these honestly:

- **A read costs about 2.2 ns, and every type costs the same.** There is no
  slow getter. Whatever you read, it is a single hash lookup and a field
  access.
- **It is about twice as fast as `Properties` plus a parse** (2.2 ns against
  4.8 ns), which is the comparison that matters if you are deciding between
  this library and doing it by hand. And that is before counting what the
  parse-free path buys you: the value was validated at startup, so it cannot
  throw here.
- **It is *not* faster than a bare `HashMap`** (2.24 ns against 2.10 ns).
  It cannot be, structurally: `getInt` does a `HashMap` lookup *and then* a
  field dereference, so it is a hash map plus a little. The gap is about 0.14
  ns - real, repeatable, and far too small to care about.
- **A read is roughly 6x a field access** (2.24 ns against 0.36 ns), not
  within a rounding error of one.
- **Caching does help in a genuinely hot loop.** Ten reads cost 22.5 ns; ten
  reads of a value copied into a local cost 2.3 ns. That is close to 10x. If
  you are reading a config value inside a tight loop, hoist it - not because
  2.2 ns is slow, but because it is 2.2 ns *every time* and a local is free.

For almost all code none of this matters: 2.2 ns is far below the noise of
anything that touches a network, a disk, or a lock. The reason to hoist a value
is readability as much as speed.

```
Benchmark                                 (propertyCount)  Mode  Cnt  Score   Error  Units
StartupBenchmark.createConfig                          10  avgt   10  0.046 ± 0.001  ms/op
StartupBenchmark.createConfig                         100  avgt   10  0.167 ± 0.005  ms/op
StartupBenchmark.createConfig                        1000  avgt   10  1.454 ± 0.050  ms/op
StartupBenchmark.createConfigWithEscapes               10  avgt   10  0.054 ± 0.004  ms/op
StartupBenchmark.createConfigWithEscapes              100  avgt   10  0.227 ± 0.008  ms/op
StartupBenchmark.createConfigWithEscapes             1000  avgt   10  2.140 ± 0.041  ms/op
```

- **Startup scales roughly linearly** with the number of properties, at about
  1.4 microseconds each.
- **A realistic configuration costs well under a millisecond**, which is
  nothing next to JVM startup.
- **Escape sequences add roughly 50%** at 1000 properties. Unescaping is the
  most expensive part of parsing a value.

## Comparison with other config libraries

`ComparisonBenchmark` reads the same configuration through ten libraries.
Before reading the numbers, know what is being compared - they are not doing
the same amount of work, and almost all of the spread is a design choice rather
than one implementation being better written than another.

| library | a read is | looks up a name? | values parsed |
|---|---|---|---|
| Jackson (bound record) | a field access | **no** - chosen at bind | once, at bind |
| Archaius | reading a handle you hold | **no** - chosen when the handle is taken | once, at load; handle updated on change |
| rwConfig | a map lookup of a typed value | yes | once, at load, against a declared type |
| avaje-config | a map lookup, converted | yes | on read |
| `java.util.Properties` | a map lookup, then your own parse | yes | never - you parse |
| SmallRye Config (MicroProfile) | a lookup, converted | yes | on read |
| Typesafe Config (HOCON) | a path parse, then a lookup | yes | once, at load |
| Spring `Environment` | a walk of ordered property sources, converted | yes | on read |
| Commons Configuration | a lookup, converted | yes | on read |
| Owner | a dynamic proxy call, converted | yes (behind the proxy) | on read |

Each library is used the way its own documentation recommends. That matters
most for Archaius, whose idiom is to obtain a `DynamicIntProperty` once and
hold it, and for Jackson, which stands in for "bind the config into a record at
startup and read fields afterwards".

Versions: rwConfig 1.0.0-SNAPSHOT, Typesafe Config 1.4.3, Commons
Configuration 2.11.0, Owner 1.0.12, Spring 6.1.14, SmallRye Config 3.10.2,
Archaius 0.7.7, avaje-config 4.0, Jackson 2.18.2. JDK 26, Apple M-series
laptop, 2 forks, 5 warmup and 5 measurement iterations.

> **Every library has its own `@State` class, and that matters.** An earlier
> version of this benchmark built all ten in one shared state. Because JMH runs
> `@Setup` for the whole state in the fork that measures each benchmark, simply
> having nine other config libraries loaded inflated every map-lookup read by
> about 1.3 ns - rwConfig measured 4.2 ns instead of 2.5. A plain `HashMap.get`
> in the same JVM slowed by an identical amount, which is what identified the
> cause: enough libraries hammering shared JDK call sites such as
> `HashMap.get` pushes them from monomorphic to megamorphic and the JIT stops
> inlining. `OverheadProbe` measures this directly, and is worth re-running if
> you add a library here.

### Reading a value

```
READ AN INT                                                lower is better
  Jackson (bound record)  # 0.4 ns
  Archaius (held handle)  # 1.5 ns
  rwConfig                # 2.5 ns
  avaje-config            # 3.7 ns
  java.util.Properties    # 5.0 ns
  SmallRye Config         ## 8.9 ns
  Typesafe Config         #### 16.2 ns
  Spring Environment      ######### 32.4 ns
  Commons Configuration   ########## 37.0 ns
  Owner                   ############################################## 168.9 ns

READ A STRING                                    (bar capped, Owner is off-scale)
  Jackson (bound record)  # 0.5 ns
  Archaius (held handle)  ## 1.3 ns
  rwConfig                ### 2.4 ns
  java.util.Properties    ### 2.5 ns
  avaje-config            ### 2.6 ns
  SmallRye Config         ####### 5.4 ns
  Spring Environment      ############### 11.8 ns
  Typesafe Config         ################### 15.6 ns
  Commons Configuration   ##################################### 29.5 ns
  Owner                   ##############################################> 4,978.2 ns
```

The ordering follows the design, in three bands:

1. **No lookup at read time at all (0.4-1.5 ns).** Jackson hands you a record
   and Archaius hands you a handle, so a read is a field access. **They are not
   answering the same question as the rest of the table** - see below.
2. **A map lookup (2.4-5.0 ns).** rwConfig, avaje-config and plain
   `Properties`. rwConfig leads this group, but on strings the three are within
   0.2 ns of each other, which is not a difference worth choosing a library
   over.
3. **A lookup plus conversion or path parsing (5-37 ns), or a proxy (169 ns).**
   Everything else. Converting on read is what buys those libraries the ability
   to change values without rebuilding anything.

#### Why the first band is not apples to apples

Every library in bands 2 and 3 answers "give me the value called *this name*",
where the name is an argument and could be computed at run time. Jackson and
Archaius answer a narrower question: "give me *the value I already picked*".
The name was resolved earlier - when the record was bound, or when the handle
was taken - so the read has nothing left to look up.

That is a real advantage when it fits, and it is why those two are quick. But
it is not the same operation, and the gap disappears as soon as the name is not
known ahead of time. Asking Archaius for a property by name on each read, which
is the question the others are answering:

```
  Archaius, handle held once      1.51 ns
  Archaius, looked up by name     8.84 ns      <- same library, same value
  rwConfig                        2.58 ns
```

Nearly 6x its own handle read, and slower than every library in band 2. Jackson
cannot be measured this way at all: a bound record has no way to answer "the
property named `s`" without reflection, because the names became fields at
compile time.

So read the first band as *"if you can decide which properties you need up
front, you can stop paying for lookups"* - which is a design decision available
to users of any of these libraries, rwConfig included, rather than a property
of Jackson or Archaius.

The honest summary for rwConfig: fastest of the libraries that look values up
by name, and beaten by designs that avoid the lookup entirely - at the cost of
having to name every property in advance.

### Loading the configuration

```
LOAD 100 PROPERTIES                                        lower is better
  Jackson bind (from a map)         2.67 us
  SmallRye Config (from a map)      3.39 us
  java.util.Properties (from file) 16.22 us
  Typesafe Config (from file)      69.42 us
  Commons Configuration (file)     75.19 us
  rwConfig (from file)            169.51 us
```

> Jackson and SmallRye are building from an already-parsed `Map`, with no file
> reading or format parsing. They are not comparable to the four that read and
> parse a file; they are shown to size the binding step itself.

Among the file-based four, **rwConfig is the slowest to load - about 10x plain
`Properties` and 2.4x Typesafe Config.** That is the other half of the same
trade: it is reading a schema, resolving sources in priority order, and parsing
and validating every value against a declared type and its allowed values. The
others do less, and the convert-on-read ones defer what they do.

**You pay roughly 170 microseconds once to save 2-35 nanoseconds per read and
to have misconfiguration fail at startup.** For a long-running service that is
the right way round. For a short-lived command reading three values it is not,
and the load cost is the number to look at rather than the read.

### A quirk worth knowing about Typesafe Config

```
  rwConfig     getInt("alphaValue")     2.5 ns      getInt("prop50")     2.3 ns
  Typesafe     getInt("alphaValue")    16.2 ns      getInt("prop50")   228.4 ns
```

Typesafe Config parses the path on every read, and is roughly **10x slower when
a path element contains a digit** - a larger difference than any between
libraries here. Keys like `oauth2ClientId`, `worker1` and `s3Bucket` are
common, so this is not a contrived case. The cause is in
`PathParser.looksUnsafeForFastParser`, whose character loop accepts letters,
`_`, `-` and `.` but not digits, so a digit disables the fast path.

This is why the benchmarks above use digit-free key names. An earlier draft
read `prop50`, which made Typesafe Config look about 70x slower than rwConfig
instead of roughly 6x. If you benchmark config libraries yourself, check that
your key names are not doing the measuring for you.

## Adding a benchmark

Put a `@Benchmark` method in a class in this module and rebuild. A few things
to keep right:

- **Return the result.** JMH consumes returned values so the JIT cannot delete
  the work. A benchmark that computes and discards may measure nothing at all.
- **Do the setup in `@Setup`.** Anything inside the measured method is part of
  the measurement - writing a file there measures the filesystem.
- **Compare against a reference point.** A number on its own says little; the
  useful output is `x` against `y` on the same machine in the same run.
