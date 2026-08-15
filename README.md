# rwConfig
rwConfig is a simple, lightweight library that provides a unified interface for
reading static configuration information in your projects.

rwConfig retrieves properties from any number of configuration sources,
validates that all properties are set and contain appropriate values,
prioritizes configuration sources so that a property from Source A can override
the same property in Source B if needed, and presents a small, unified, _fast_
interface for value retrieval.

## How Fast Is It?
For arbitrary reads it's already faster than the other popular config libraries
tested. The reason is this library validates everything up front; it trades
up-front cost for a vastly reduced cost at retrieval time. And that cost is spent
only once--not on every read of a property. If you're creating a long-lived app
that may ultimately do a lot of reads, this is the way to go.

As a bonus, reads times are consistent no matter what you're reading or where
you're getting it from.

Don't take my word for it. [Run the benchmarks yourself!](benchmark) Or just look
at the [takeaway](benchmark/README.md).

## Features
- fast reads
  - reading and parsing of configuration sources and property values happens at
    startup only
  - after initialization, it's essentially just a flat, unmodifiable HashMap of
    String keys to values
    - see the "Design Choices" section for more details
- simple interface for property retrieval
  - since the library does the work of determining property types, allowed
    values, and default values at initialization, the API side doesn't need to
    deal with `Optional`s or a long, fluent chain of methods just to get a
    simple value
  - on the API side, it's a similar API to `java.util.Map` with types
    tacked on
- self-documenting
  - the `rwconfig` file provides a single source of truth for information about
    all of your application's properties and where to find values for them:
    - property names
    - property types (`boolean`, `int`, `long`, `double`, `string`, and list
      versions of these types)
    - allowed values (optional)
    - default values (optional)
    - where to retrieve property values (the configuration sources)
- simple, declarative setup
  - the `rwconfig` file syntax is similar to Java `.properties` files, but the
    syntax is expanded to optionally include types, allowed values, and default
    values
  - you can set property values directly in the `rwconfig` file, or define how
    to retrieve them
- lightweight
  - no dependencies outside of the Java Base module (and slf4j, which also only
    requires Java Base)
- support for multiple configuration sources
  - built-in support for Java `.properties` files and directory-based
    configuration
  - support for loading app properties from YAML, JSON, XML, and databases using
    the supplied plugins
  - built-in support for loading configuration files from the filesystem,
    a `jar` file on the filesystem, http(s), or the classpath
  - support for command line arguments, environment variables, and JVM system
    properties
  - simple plugin API for creating custom configuration sources
- fail-fast design - the library is designed to detect configuration issues
  sooner rather than later
  - most configuration issues are detected at startup
- property types and value validation
  - current supported types are `boolean`, `int`, `long`, `double`, and `string`
  - list support (`booleanList`, `intList`, etc.)

## Project Goals
- a simple interface with virtually no learning curve
- lightweight
- high-speed retrieval of property values
- fail-fast behavior - detect errors at startup whenever possible
  - a compile-time plugin is also planned to detect when the Java side expects
    a non-existent property or an incorrect property type
- more secure
  - values that aren't expressly needed are not added to the Config object, even
    if present in the configuration source

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

## Quick Start
### 1. Jar Installation (via Maven)
Add this to your project's pom.xml:

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
`resources` folder of your Maven project. You can optionally define a custom
path to this file by setting the environment variable `RW_CONFIG_PATH`, setting
the Java system property `rw.config.path`, or adding the command line argument
`rw.config.path=/path/to/rwconfig`:

```
# sample config setup
rwc.sources = args, system, environment
rwc.args.type = commandLineArguments
rwc.system.type = systemProperties
rwc.environment.type = environmentVariables

# example properties; your properties can be anything you want
int[80, 1024:65535] port = 8000
DBPassword
```

Here's a heavily commented version of the same file that elaborates on what's
going on:

```
# sample config setup

# Declare the names and priorities (highest to lowest) of any additional sources
# to check for configuration properties.
rwc.sources = args, system, environment

# Declare information about those sources. The type of the source is always
# required. Other information may be needed as well, depending on the source
# type.
#
# For more information on all of the built-in source types--as well as how to
# create a custom source type plugin--see the `rwconfig` file in the example
# project's `resources` folder
rwc.args.type = commandLineArguments
rwc.system.type = systemProperties
rwc.environment.type = environmentVariables


#
# All properties used by your app need to be *defined* in this file, but you are
# not required to give the property a value here. The property's value can be 
# set (or overridden) in one of the configuration sources listed above.
#
# The key thing to remember about this file is that its primary purpose is *not*
# to provide values for your app's properties. Its purpose is to define what
# properties your app requires, what they should look like, and where they can
# be found.
#


# What port should our app use. Valid values are integers, and we only want to
# allow certain integer values. Define the allowed values as 80, and any value
# between 1024 and 65535. The default value is 8000.
int[80, 1024:65535] port = 8000

# Declare that we need a database password. Sensitive information like passwords 
# should not be present in shared files, so we'll leave the value unset.
#
# This means that the value of `DBPassword` will have to be set in one of the
# declared sources above. It can be set on the command line using an argument in
# the form `DBPassword=mySecretPassword`, by setting a Java system property of
# the same name, or by setting an environment variable. The environment variable
# name can be the exact same name or the normalized form of `DB_PASSWORD`.
#
# You could also set the value of `DBPassword` in a separate, more secure file.
# That would require adding another configuration source above. Details on how
# to do this are in the sample `rwconfig` file in the example project's
# `resources` folder.
#
# If this property's value is *not* set in a declared source, the library will
# throw an exception at startup.
#
# By the way: you don't need to supply a type when you define a property as we
# did with `port` above. If you don't supply the type, a type of `string` is
# assumed. The below property could also be written as `string DBPassword`.
DBPassword
```

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
configuration source, so for that example to work you must use the above version
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

- default values are declared in the config file--not in the code
- missing or incorrect property values are caught at startup
- expecting an incorrect property type in Java code is always an error
- requesting an unknown property in Java code is always an error

These errors are treated as unchecked exceptions because (a) they are avoidable
at coding time, and (b) encountering them at runtime guarantees that the code is
not working as the developer intended from that point on.

## Should I Rip Out My Old Config System And Use It?
The short answer is "probably not". The more accurate answer is "it depends,
but probably not". It's generally not worth the effort to make that kind of
change in an existing project, and if you recommend an immature library for a new
production-level project people build will correctly think you're crazy. Maybe
play around with it on your home project for a while and give me some feedback.
See [Choosing a Configuration Library](docs/comparison.md).

## TODOs
- IDE/Maven support to detect missing properties and incorrect property types at
  compile time
- in-app notification of changed configuration sources
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
- simple, easy to learn config file syntax
- declarative rather than code-based configuration
  - avoid `Optional`s and long method chains in the code by declaring types and
    default values in the config file
      - the use of `Optional` and `orElse` encourages the "magic number"
        anti-pattern
  - avoid the need to recompile just to tweak a configuration source or change
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
- [Plugins](PLUGINS.md) - the YAML, JSON, XML, JDBC and prefix plugins that
  ship with the project

There is also a heavily commented [sample `rwconfig`
file](example/src/main/resources/rwconfig) that exercises nearly every feature,
and a [runnable example application](example) that loads it.
