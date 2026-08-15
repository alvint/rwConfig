# Choosing a Configuration Library

An honest comparison of rwConfig against the libraries it is benchmarked
against, and when each is the right choice.

This is written by rwConfig's own project, so treat the praise sceptically and
the criticism as probably understated. Every number here comes from
[the benchmark module](../benchmark), which you can run yourself. Everything
else is judgement, and is marked as such.

**The short version:** if you already use a framework with configuration built
in, use that. If your configuration is a document that maps to a class, bind it
with Jackson. rwConfig is worth considering when you want every property
declared, typed and validated in one file, and misconfiguration to stop the
application at startup rather than surprise you later.

## Start here

| if... | use |
|---|---|
| you use Spring or Spring Boot | Spring's `Environment` and `@ConfigurationProperties` |
| you use Quarkus, or want the MicroProfile standard | SmallRye Config |
| you use Akka, Pekko or Play | Typesafe Config - it is already there |
| your config is a YAML or JSON document mapping to a class | Jackson (or your framework's binder) |
| values must change while the application runs | Archaius 2, or Commons Configuration's reloading |
| you must read INI, plist, XML or other legacy formats | Commons Configuration |
| you have a handful of values and want zero dependencies | `java.util.Properties` |
| you want one file that declares, types and validates every property, and fails fast | **rwConfig** |
| you want something small and modern without a framework | avaje-config, or rwConfig |

Most of these are not close calls. Fighting a framework's own configuration
system to use a different one is rarely worth it.

## At a glance

| | learning curve | read | load | validation | layered sources | reload | maturity |
|---|---|---|---|---|---|---|---|
| **rwConfig** | small, but a new file format | 3.3 ns | 209 us | **types + allowed values, at startup** | built in | no | **new, unproven** |
| Typesafe Config | moderate - HOCON, path semantics | 17 ns | 62 us | optional, limited | merge/fallback | no | very mature, widely used |
| Commons Configuration | large, sprawling API | 44 ns | 84 us | none | composite config | yes | very mature |
| Spring `Environment` | large, if you are not already in Spring | 35 ns | n/a | via binding + JSR-380 | property sources | yes | very mature, huge ecosystem |
| SmallRye Config | moderate | 10 ns | 3.8 us* | converters, `@ConfigMapping` | ordinal-based | partial | mature, MicroProfile standard |
| Archaius 2 | moderate | 1.6 ns** | n/a | none | composite | **yes, by design** | current line, Netflix-proven |
| Archaius 1.x | moderate | 1.5 ns** | n/a | none | composite | **yes, by design** | legacy; use 2.x for new work |
| avaje-config | small | 4.1 ns | n/a | none | built in | yes | small but active |
| Owner | very small - declare an interface | 248 ns | n/a | via converters | some | some | quiet since 2023 |
| `java.util.Properties` | none | 7.3 ns | 23 us | none | none | no | it is the JDK |
| Jackson (bind a record) | small if you know Jackson | 0.5 ns** | 3.2 us* | via the type, plus JSR-380 | none - you build it | no | very mature |

\* built from an already-parsed map, so not comparable to the file-based
figures. \*\* not looking up a name - see below.

Read figures are an `int` read from a 100-property config, JDK 26, Apple
M-series. **The read column matters far less than it looks.** Everything above
is between 0.5 and 250 nanoseconds; for almost any application that difference
is invisible next to a single log line. Weigh the other columns first.

### About those two fast outliers

Jackson and Archaius are not answering the same question as everything else.
Their reads do not look up a name - it was chosen earlier, when the record was
bound or the handle taken. Ask Archaius for a property *by name* on each read,
which is what the others do, and it costs 9.9 ns in 1.x and 25.9 ns in 2.x,
rather than 1.5. A bound record cannot answer that question at all without
reflection.

## The libraries

### Typesafe Config (HOCON)

The closest thing the JVM has to a default. HOCON is a genuinely good format -
comments, includes, substitutions, and merging of several files into one view.
The library is mature, well documented and everywhere.

**Strengths:** the format; wide adoption, so people already know it; merging
and fallbacks are excellent; hierarchical config is first class.

**Weaknesses:** no schema, so a typo in a key is only found when something asks
for it; reads parse the path every time, and are roughly 10x slower when a key
contains a digit, because a digit disables its fast path for parsing a path
(measured in [the benchmark](../benchmark/README.md)); no built-in reload.

**Choose it when** you want a rich file format, you are in the Akka/Pekko/Play
world, or you want the option with the largest community.

### Apache Commons Configuration

The everything-format library. Properties, XML, INI, plists, JNDI, JDBC, and
more, behind one interface, with builders and reloading.

**Strengths:** format coverage nothing else matches; reloading; genuinely
mature; Apache governance means it will outlive most alternatives.

**Weaknesses:** the slowest reads here apart from Owner, because it stores
strings and converts on every call; a large API surface to learn; needs
`commons-beanutils` at runtime for the builders, which is easy to miss.

**Choose it when** you must read formats other libraries do not support, or you
want reloading without extra machinery.

### Spring `Environment` and `@ConfigurationProperties`

**Strengths:** already present in any Spring application; profiles; relaxed
binding; ordered property sources; binding to POJOs with JSR-380 validation;
the ecosystem is vast.

**Weaknesses:** only sensible inside Spring - as a standalone config library it
is a heavy dependency; `Environment.getProperty` converts on each call, though
`@ConfigurationProperties` binding gives you field reads.

**Choose it when** you are using Spring. Do not fight it.

### SmallRye Config (MicroProfile Config)

The reference implementation of the MicroProfile Config standard, and Quarkus's
configuration layer.

**Strengths:** a vendor-neutral standard API; good converter support;
`@ConfigMapping` binds to interfaces and records, giving field-speed reads;
solid performance; actively developed.

**Weaknesses:** most natural inside Quarkus or a MicroProfile server; the spec
constrains parts of the API; ordinal-based source precedence takes a moment to
learn.

**Choose it when** you use Quarkus, or you want an API that is a standard
rather than one library's opinion.

### Netflix Archaius

Built for configuration that changes while the application runs. You take a
handle to a property once and it stays current as the underlying source
changes. Both lines are benchmarked: **Archaius 2** is the current one, and
**1.x** is the legacy line still found in older Netflix-stack applications.

**Strengths:** dynamic properties done properly, with callbacks; the handle
read is one of the fastest measured here, in both lines; battle-tested at
Netflix scale.

**Weaknesses:** the model is more complex than most applications need. **You
must hold the handle** - asking for a property by name on each read gives up
the entire benefit, and in Archaius 2 that path is markedly more expensive than
in 1.x. The two lines have different APIs, so moving between them is a
migration rather than a version bump.

**Choose it when** you genuinely need values to change without a restart -
feature flags, tuning knobs in a long-running service. Prefer 2.x for new work.

### avaje-config

Small, modern and quick, without a framework attached.

**Strengths:** easy to learn; good performance; supports properties and YAML;
layered sources and reloading; actively maintained.

**Weaknesses:** a small community, so fewer answers when you get stuck; no
schema or validation.

**Choose it when** you want something light and current and do not need
declared types or validation. It is the closest neighbour to rwConfig in
spirit, and better established.

### Owner

You declare an interface, annotate it, and the library implements it.
Ergonomically this is lovely - the API *is* your config, with IDE completion
and compile-time names.

**Strengths:** the nicest declaration model here; almost no learning curve;
defaults, key names and converters are all annotations.

**Weaknesses:** by far the slowest reads measured - 248 ns for an `int` and
about 7 microseconds for a `String`, through the dynamic proxy; last release
was 1.0.12 in early 2023, so it is quiet.

**Choose it when** ergonomics matter more than anything else and configuration
is read rarely - which is the normal case, so do not dismiss it over the
numbers.

### `java.util.Properties`

**Strengths:** no dependency, no learning curve, universally understood.

**Weaknesses:** strings only; no types, lists, defaults, validation, layering
or structure. Everything above that, you write and maintain yourself.

**Choose it when** you have a handful of values, or a dependency would cost
more than the code it saves.

### Jackson, binding to a record

Not a configuration library - a serialization library used as one, which is
extremely common.

**Strengths:** your config becomes a typed object; reads are field accesses;
IDE completion and safe refactoring; JSON, YAML and TOML via dataformat
modules; JSR-380 validation if you want it.

**Weaknesses:** none of the *configuration* parts - no layered sources, no
environment variable overlay, no precedence rules. You build those yourself,
and they are most of what a config library does. Values are frozen at bind
time.

**Choose it when** your configuration is a document that maps cleanly to a
class, and one source is enough.

## rwConfig

### Strengths

- **One file describes everything.** Names, types, allowed values, defaults and
  where values come from, all in one place. Nothing is spread between a
  properties file and a constants class.
- **Misconfiguration fails at startup**, naming the property. A missing value,
  a wrong type, a value outside its allowed range, and - uniquely here - a
  property in a source that no declaration mentions, which catches typos.
- **The read API is as simple as it gets.** `config.getInt("port")` returns an
  `int`. No `Optional`, no cast, no default at the call site, no exception to
  handle.
- **Layered sources are built in** - command line, environment, system
  properties, files, directories, databases - with priority you declare, plus
  `<<` for keeping secrets out of shared files.
- **Fastest of the libraries that look values up by name**, though the margin
  is small enough not to matter.
- **Small.** No dependencies beyond slf4j.

### Weaknesses

- **It is new and unproven.** Version 1.0.0-SNAPSHOT, one author, no production
  track record, no community. Every other library here has years of use behind
  it. This is the strongest argument against adopting it, and no benchmark
  offsets it.
- **The slowest to load** - about 210 microseconds for 100 properties, roughly
  9x plain `Properties`. It is doing more, but it is still the slowest.
- **No runtime reload.** The plugin API has the hooks and the README plans it,
  but nothing implements it. If values must change without a restart, this is
  the wrong library today.
- **No framework integration.** Nothing binds it to Spring, Quarkus, Micronaut
  or anything else.
- **No binding to a class.** Reads are by name, so you get neither compile-time
  property names nor IDE completion.
- **A bespoke file format to learn.** Small, and close to `.properties`, but it
  is one more thing, and only this project's editor extension understands it.
- **Limited types.** No durations, sizes, enums or `URI`. `int timeoutSeconds`
  works but is not what most people want to write.
- **Plugins require the module path**, which is a real constraint for
  applications still on the classpath.

### When it is a good fit

- A long-running service, not in a framework that already solves this.
- You want configuration errors to stop the process at startup rather than
  cause odd behaviour later.
- You want the config file to be the documentation, so a new team member can
  read one file and know every property the application takes.
- You want layered sources without assembling them yourself.
- You value a small dependency footprint.

### When to pick something else

- **You use Spring, Quarkus, Micronaut, Akka or Play.** Use what is there.
- **Values must change without a restart.** Archaius 2, or Commons
  Configuration.
- **You cannot take a risk on a young library.** Entirely reasonable - take
  Typesafe Config or Commons Configuration.
- **You want config bound to a class** with compile-time names. Jackson,
  SmallRye's `@ConfigMapping`, Spring's `@ConfigurationProperties`, or Owner.
- **You need HOCON's includes and substitutions.** Typesafe Config.
- **Your configuration is three values.** `Properties` is fine.

## On the numbers in this document

Performance was the easiest thing to measure and is the least important thing
here. It is included because it can be checked, not because it should decide
anything. A 30-nanosecond difference in a config read will never be why an
application is slow.

The columns that should decide it are the ones that cannot be benchmarked: does
it fit the framework you already use, will it still be maintained in five
years, will the next person understand it, and does it catch mistakes before
they reach production.

See [the benchmark README](../benchmark/README.md) for the full figures, the
methodology, and the ways these measurements can mislead - including two that
misled us before they were caught.
