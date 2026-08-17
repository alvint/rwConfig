# What is rwConfig?
![Java](https://img.shields.io/badge/Java-21%2B-blue)
![Tests](https://img.shields.io/badge/tests-346-brightgreen)
![Runtime deps](https://img.shields.io/badge/runtime%20deps-slf4j%20only-brightgreen)
![Status](https://img.shields.io/badge/status-pre--1.0-orange)

rwConfig is a simple, lightweight library that provides a unified and _fast_
interface for reading static configuration information from a wide range of
config sources.

What distinguishes rwConfig from the pack is its design philosophy: define
what you need and what it should look like ahead of time, and validate that
you have what you need on startup. This means that you can rest easier knowing
that you won't be surprised by configuration issues when it's too late to do
something about it.

## In A Nutshell
Declare what your app needs, in one file. This is a complete, working
`rwconfig`:

```
int[80, 1024:65535] port = 8000
```

`port` is an `int`, it may only be 80 or a value from 1024 to 65535, and it
defaults to 8000.

Add a couple of lines to say where values may come from, and a property that
must be supplied rather than defaulted:

```
rwc.sources = environment
rwc.environment.type = environmentVariables

int[80, 1024:65535] port = 8000
DBPassword
```

`DBPassword` deliberately has no value here--it has to come from a config
source, which in this file means the environment variable `DB_PASSWORD`. Miss
it and the application refuses to start, rather than running with a blank
password. Sources can equally be the command line, a file you don't commit, a
directory, or a database; you list the ones you want in `rwc.sources`, best
first.

Read it with no ceremony:

```java
int port = config.getInt("port");
```

No `Optional`. No cast. No default value at the call site. No exception to
handle. The type, the default, and the allowed values were all settled before
your first line of code ran.

And when the configuration is wrong, you hear about it at startup--not at 3am:

```
value is not allowed for property `port` (in source `args`): 500
```
```
property `DBPassword` is not set by any config source, and has no default value defined in the `rwconfig` file
```

## How Fast Is It?
| reading an `int` by name | |
|---|---|
| **rwConfig** | **3.3 ns** |
| avaje-config | 4.1 ns |
| `java.util.Properties` | 7.3 ns |
| SmallRye Config | 10.2 ns |
| Typesafe Config | 16.5 ns |
| Spring `Environment` | 35.1 ns |
| Commons Configuration | 44.1 ns |
| Owner | 248.2 ns |

Fastest of the libraries that look a value up by name. Some libraries are
faster still by not looking anything up at read time--the
[benchmark README](benchmark/README.md) is honest about which, and why that
isn't the same question.

For arbitrary reads it's already faster than the other popular config libraries
tested. The reason is this library validates everything up front; it trades
up-front cost for a reduced cost at retrieval time. And that cost is spent only
once--not on every read of a property. If you're creating a long-lived app that
may ultimately do a lot of reads, this definitely doesn't hurt.

As a bonus, read times are consistent no matter what you're reading or where
you're getting it from.

Don't take my word for it. [Run the benchmarks yourself!](benchmark) Or just look
at the [takeaway](benchmark/README.md).

## Features
- **Every property declared in one file** - name, type, allowed values, default
  value, and where to look for it. The `rwconfig` file is a single source of
  truth, and doubles as your configuration documentation.
- **Errors at startup, not at 3am** - missing values, unparseable values, values
  outside their allowed range, wrong types on the Java side, and requests for
  properties that no declaration mentions.
- **Fast, uniform reads** - a read is one HashMap lookup of an already-parsed
  value: ~2.3 ns whether it's an `int`, a `String`, or a list.
- **No `Optional`s, no fluent chains** - `config.getInt("port")` returns an
  `int`, because types, defaults, and validation were settled at startup. On the
  API side it's a similar API to `java.util.Map`, with types tacked on.
- **Types and lists** - `boolean`, `int`, `long`, `double`, `string`, `size`,
  and `duration`. Also `booleanList`, `intList`, `longList`, `doubleList`,
  `stringList`, `sizeList`, and `durationList`.
- **Layered sources, with precedence you declare** - command line arguments,
  environment variables, system properties, `.properties` files, and
  directories. Plus YAML, JSON, XML, HOCON, and databases via the bundled plugins.
  Load them from the filesystem, a `jar`, http(s), or the classpath. Add your own
  with a small plugin API.
- **Nearly dependency-free** - the Java Base module and slf4j, which itself only
  requires Java Base.
- **Secure by omission** - values your app never declared are not added to the
  Config object, even when the config source contains them.

## Quick Start
### 1. Jar Installation (via Maven)
rwConfig isn't on Maven Central yet - that's waiting on a version stable enough
to call `1.0.0`. Until then, clone the repo and install it into your local Maven
repository:

```
git clone https://github.com/alvint/rwConfig.git
cd rwConfig
mvn install
```

Then add this to your project's pom.xml:

```xml
<dependencies>
    ...
    <dependency>
        <groupId>net.rabbitware.config</groupId>
        <artifactId>config</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### 2. Create a File Called `rwconfig`
Place this file in the working directory where your app will run, or in the
`resources` folder of your Maven project. Either works; if you have both, the
`resources` copy wins, since the classpath is checked first. You can optionally
define a custom path to this file by setting the environment variable
`RW_CONFIG_PATH`, setting the Java system property `rw.config.path`, or adding
the command line argument `rw.config.path=/path/to/rwconfig`. If you set more
than one, the command line wins, then the system property, then the environment
variable.

A file of nothing but declarations is valid - every property then takes the
default declared for it:

```
# a complete rwconfig file
int[80, 1024:65535] port = 8000
```

Declare config sources when you want values to come from somewhere else. They
are listed highest precedence first, and each one says what type it is:

```
# sample rwconfig file
rwc.sources = args, system, environment
rwc.args.type = commandLineArguments
rwc.system.type = systemProperties
rwc.environment.type = environmentVariables

# example properties; your properties can be anything you want
int[80, 1024:65535] port = 8000
DBPassword
```

Two files in the example project sit at either end of the scale: a
[minimal `rwconfig`](example/src/main/resources/rwconfig-minimal) that is close
to the smallest one worth writing, and a [heavily commented
`rwconfig`](example/src/main/resources/rwconfig) that exercises nearly every
feature. The format is documented in full in
[The `rwconfig` File](docs/config-file.md).

### 3. Basic Usage
**Creating the Config Object**

If you want to allow configuration properties to be overridden on the command
line:

```java
import net.rabbitware.config.*;

...

Config config = ConfigFactory.create(commandLineArguments);
```

If you **don't** want to allow configuration properties to be overridden on the
command line, use the below code to create the Config object instead.

Note that the `rwconfig` example above defines the command-line arguments as a
config source, so for that example to work you must use the above version
 of `create`:

```java
import net.rabbitware.config.*;

...

Config config = ConfigFactory.create();
```

**Retrieving a Value**

Retrieving a property is painless:
```java
// get the value of the property `port`
int port = config.getInt("port");

// get the type of the property `port`
Config.PropertyType propertyType = config.getType("port");

System.out.println("type of `port`: " + propertyType.name);
System.out.println("value of `port`: " + port);
```
There's no need to deal with `Optional`s here because the library handles
property declarations, default values, and value types at startup. This means:

- default values are declared in the `rwconfig` file--not in the code
- missing or incorrect property values are caught at startup
- expecting an incorrect property type in Java code is always an error
- requesting an unknown property in Java code is always an error

These errors are treated as unchecked exceptions because (a) they are avoidable
at coding time, and (b) encountering them at runtime guarantees that the code is
not working as the developer intended from that point on.

## Should I Rip Out My Old Config System And Use It?
The short answer is "probably not". The more accurate answer is "it depends,
but probably not". It's generally not worth the effort to make that kind of
change in an existing project, frameworks normally have their own "blessed"
config systems and it's best not to swim against the tide, and if you recommend
an immature library for a new production-level project people will correctly
think you're crazy. Maybe just play around with it on your home projects and give
me some feedback. See [Choosing a Configuration Library](docs/comparison.md).

## Does It Matter How Fast A Config System Is?
Not really. But some people like numbers. What _really_ matters is the design
philosophy. It's better to define up-front what your config properties should
look like and how they should be loaded. And it's _much_ better to test if your
config is up to snuff at app start (when you're in the office), than to find out
at 3am.

## Project Goals
- a simple interface with virtually no learning curve
- lightweight
- high-speed retrieval of property values
- fail-fast behavior - detect errors at startup whenever possible
  - a compile-time plugin is also planned to detect when the Java side expects
    a non-existent property or an incorrect property type
- more secure
  - values that aren't expressly needed are not added to the Config object, even
    if present in the config source

## Project _Non_-goals
- a "one-size fits all" approach
  - This is how I prefer to configure _my_ apps. I'm not going to make the API
    or configuration more complex to cover use cases that would be of marginal
    value to me.
- a way to set properties within the app
  - That would require the Config object to be mutable, and _that_ creates many
    "what if" scenarios involving thread synchronization, guaranteeing atomic
    behavior to clients of the API, etc.

    The closest to supporting this I plan to come is notifying clients of the
    API when a config source (for example, a `.properties` file) has changed.
    The client can then choose to discard the old Config object and create a new
    one. Yes, that's not very close at all.
- support for an in-memory hierarchical data structure
  - hierarchical data sources (like YAML, JSON, and XML) are "flattened" during
    ingestion
    - reads are far faster this way
    - there is effectively no difference to clients of the API when retrieving a
      value by its key
  - see the "Design Choices" section for more details
- anything that would greatly increase size, or add dependencies to this project
  outside of Java Base

## TODOs
- IDE/Maven support to detect missing properties and incorrect property types at
  compile time
- in-app notification of changed config sources
- APIs for other languages

## Design Choices and Miscellaneous Rants
- effectively immutable and atomic configuration
- "flat" data structure
  - WARNING: screed follows:

    I have no idea why many config systems work with a "node/graph" structure
    instead of a flattened structure. It is more efficient overall to "flatten"
    graph structures (like JSON files) than it is to do the reverse (convert
    structures like `.properties` files to nodes).

    The absolute worst-case performance of retrieving values from a flat HashMap
    is `O(log n)`, and it is often `O(1)`. That worst-case performance can get a
    bit better if you make `n` smaller by sorting different property types into
    their own buckets--something you can't do with the "node" structure.

    On the other hand, the worst-case performance of a node structure is
    `O(log n1 + log n2... + log nx)` and the best case is `O(x)`, where `x` is
    the number of levels. This doesn't cover any cost of parsing the keys while
    doing the node navigation. Any edge-case advantage of keeping the hierarchy
    intact is (IMO) smaller than the performance gains from a flattened data
    structure. Plus, node navigation makes the code more complicated. Bad!

    Google's Gmail has done this for decades. As far as everything but the last
    bit of UI is concerned, your email folder structure is just a bunch of flat,
    opaque tags that happen to have forward slashes in them. The end result is
    virtually identical.

    Hierarchies and taxonomies are for **human** consumption. Computers don't
    need to care about them. If you try to force computers to work the way that
    humans do, you will get unnecessarily weaker performance.
- fail-fast behavior
- no dependencies
- compatibility with existing Java `.properties` files (some minor restrictions
  on property names)
- simple, easy to learn `rwconfig` file syntax
- declarative rather than code-based configuration
  - avoid `Optional`s and long method chains in the code by declaring types and
    default values in the `rwconfig` file
      - the use of `Optional` and `orElse` encourages the "magic number"
        anti-pattern
  - avoid the need to recompile just to tweak a config source or change
    a default value
  - no need to search through the code to find out where a value came from

## Documentation
- [Getting Started](docs/getting-started.md) - what the library is for, and a
  working configuration in a few minutes
- [The `rwconfig` File](docs/config-file.md) - the file format in full: types,
  allowed values, ranges, escapes, and splitting long lines
- [Config Sources](docs/config-sources.md) - the built-in source types, how
  precedence works, and how to keep secrets out of shared files
- [The Java API](docs/java-api.md) - everything on the `Config` object
- [Writing a Plugin](docs/writing-a-plugin.md) - adding a config source type of
  your own
- [Error Messages](docs/errors.md) - what each startup error means and how to
  fix it
- [Choosing a Configuration Library](docs/comparison.md) - how rwConfig
  compares with the alternatives, and when to use one of them instead
- [Plugins](PLUGINS.md) - the YAML, JSON, XML, HOCON, JDBC, and prefix plugins that
  ship with the project

There is also a [minimal `rwconfig`](example/src/main/resources/rwconfig-minimal)
and a heavily commented [sample `rwconfig`
file](example/src/main/resources/rwconfig) that exercises nearly every feature,
plus a [runnable example application](example) that loads it.
