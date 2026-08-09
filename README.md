# rwConfig
rwConfig is a simple, lightweight library for reading configuration information
in your projects. rwConfig retrieves properties from any number of configuration
sources, validates that all properties are set and contain appropriate values,
prioritizes configuration sources so that a property from Source A can override
the same property in Source B if needed, and presents a small, unified, _fast_
interface for value retrieval.

## Features
- simple key-value configuration management
- fast reads
  - reading and parsing of configuration sources and property values happens at
    startup only
  - after initialization, it's essentially just a flat, unmodifiable HashMap of
    String keys to values
  - specific value classes are preferred over generics to (hopefully) improve
    performance by avoiding boxing and unboxing
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
  - you can set all property values directly in the `rwconfig` file, or define how
    to retrieve them
- lightweight
  - no dependencies outside of the Java Base module
- simple interface for property retrieval
  - on the Java side, it's a similar API to `java.util.properties` with types
    tacked on
- support for multiple sources of configuration info with a clear hierarchy
  - file support (currently `.properties` files with `.yaml` files planned),
    loadable from the file system, URLs, or the classpath
  - support for command line arguments, environment variables, and JVM system
    properties
  - support for settings retrieved from a database (coming soon)
- fail-fast design - the library is designed to detect configuration issues
  sooner rather than later
  - most configuration issues are detected at startup
  - plugins to detect unexpected types and incorrect property names at compile
    time are planned
- property types and value validation
  - current supported types are `boolean`, `int`, `long`, `double`, and `string`
  - list support (`booleanList`, `intList`, etc.)
- compatible with plain Java `.properties` files (minor limits on property names)
  - `.yaml` file support is also planned

## Project Goals
- a simple interface with virtually no learning curve
- light-weight - when it comes to microservices and embedded environments, size
  matters
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
- anything that would greatly increase size, or add dependencies to this project
  outside of Java Base

## Quick Start
### 1. Jar Installation (via Maven)
Add this to your project's pom.xml:

```xml
<dependencies>
    ...
    <dependency>
        <groupId>net.rabbitware</groupId>
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
config.sources = args, system, environment
config.args.type = commandLineArguments
config.system.type = systemProperties
config.environment.type = environmentVariables

# example properties; your properties can be anything you want
int[80, 1024:65535] port = 8000
DBPassword
```

Here's a heavily commented version of the same file that explains what's going
on:

```
# sample config setup

# Declare the names and priorities (highest to lowest) of any additional sources
# to check for configuration properties.
config.sources = args, system, environment

# Declare information about those sources. The type of the source is always
# required. Other information may be needed as well, depending on the source
# type.
config.args.type = commandLineArguments
config.system.type = systemProperties
config.environment.type = environmentVariables


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
# should not be present in shared files, so we'll leave the value blank.
#
# This means that the value of `DBPassword` will have to be set in one of the
# declared sources above. It can be set on the command line using an argument in
# the form `DBPassword=mySecretPassword`, by setting a Java system property of
# the same name, or by setting an environment variable. The environment variable
# name can be the exact same name or the normalized form of `DB_PASSWORD`.
#
# You could also set the value of `DBPassword` in a separate, more secure file.
# That would require adding another configuration source above. Details on how
# to do this are in the sample `rwconfig` file in the project's `resources`
# folder.
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

IO.println("type of `port`: " + type.name);
IO.println("value of `port`: " + port);
```
There's no need to deal with `Optional`s here because the library handles
property declarations, default values, and value types at startup. This means:

- missing or incorrect property values are caught at startup
- incorrect property types in Java code is always an error
- unknown properties in Java code is always an error

These errors are treated as unchecked exceptions because (a) they are correctable
at coding time, and (b) encountering them at runtime guarantees that the code is
not working as the developer intended from that point on.

## TODOs
- a plugin system for defining custom property sources
- IDE/Maven support to detect missing properties and incorrect property types at
  compile time
- in-app notification of changed configuration sources
- APIs for other languages

## Design Choices

- effectively immutable and atomic configuration
- fail-fast behavior
- no dependencies
- compatibility with existing Java `.properties` files (some minor restrictions
  on property names)
- rwConfig's configuration file syntax builds on the well-known `.properties`
  file syntax
- configuration issues, and attempting to retrieve missing or incorrectly-typed
  properties throw unchecked exceptions
  - if you're catching these exceptions you're doing it wrong

## Documentation

TODO - see the sample `rwconfig` file in the project's `resources` folder for
more details
