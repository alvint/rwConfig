# rwConfig
rwConfig is a simple, lightweight library for reading configuration information
in your projects. rwConfig retrieves properties from any number of configuration
sources, validates that all properties are set and contain appropriate values,
prioritizes configuration sources so that a property from Source A can override
the same property in Source B if needed, and presents a small, unified, _fast_
interface for value retrieval.

## Features
- simple key-value configuration management
- no dependencies outside of the Java Base module
- fast reads
  - all reading and parsing of configuration sources happens at startup only
  - after initialization, it's essentially just a flat, unmodifiable HashMap of
    String keys to values
  - specific value classes are preferred over generics to (hopefully) improve
    performance by avoiding boxing and unboxing
- simple, declarative setup
  - the `rwconfig` file syntax is similar to Java `.properties` files, but the
    syntax is expanded to optionally include types, allowed values, and default
    values
  - you can set all property values directly in the `rwconfig` file, or define how
    to retrieve them
- simple interface for property retrieval
  - on the Java side, it's a similar API to `java.util.properties` with types
    tacked on
- self documenting
  - it enforces a centralized source of truth for information on all application
    properties (the `rwconfig` file)
- support for multiple sources of configuration info with a clear hierarchy
  - file support (currently `.properties` files with `.yaml` files planned),
    loadable from the file system, URLs, or the classpath
  - support for command line arguments, environment variables, and JVM system
    properties
  - support for settings retrieved from a database (coming soon)
- fail-fast design - most configuration issues are detected at startup
- property types and value validation
  - current supported types are `boolean`, `int`, `long`, `double`, and `string`
  - list support (`booleanList`, `intList`, etc.)
- compatible with plain Java `.properties` files
  - `.yaml` file support is also planned

## Project Goals
- a simple interface with virtually no learning curve
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

    The closest I plan to come to supporting this is by notifying clients of the
    API when a config source (for example, a `.properties` file) has changed.
    The client can then choose to reload the entire config object.
- anything that would add dependencies to this project outside of Java Base

## Quick Start
### 1. Jar Installation (via Maven)
Add this to your project's pom.xml:

```xml
<dependencies>
    ...
    <dependency>
        <groupId>net.rabbitware</groupId>
        <artifactId>config</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

### 2. Create a File Called `rwconfig`
Place this file in the working directory where your app will run, or in the
`resources` folder of your Maven project:

```
# sample config setup

# Declare the names and priorities (highest to lowest) of any additional sources
# of configuration properties.
config.sources = args, system, environment

# Declare information about those sources. The type of the source is always
# required. Other information may be needed as well, depending on the source
# type.
config.args.type = commandLineArguments
config.system.type = systemProperties
config.environment.type = environmentVariables


#
# All properties used by your app need to be *defined* in this file as well, but
# the value can optionally be set in another configuration source.
#

# What port should our app use. Valid values are integers, and we only want to
# allow certain integer values. Define the allowed values as 80, and any value
# between 1024 and 65535. The default value is 8000.
int[80, 1024:65535] port = 8000

# Declare that we need a database password. Sensitive information like passwords 
# should not be present in shared files, so we'll leave the value blank. This
# means that the value of `DBPassword` will have to be set elsewhere.
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

If you **don't** want to allow configuration properties to be overridden on the command
line:

```java
import net.rabbitware.config.*;

...

Config config = ConfigFactory.create();
```

Retrieving a property is painless:
```java
// get the value of the property `port`
int port = config.getInt("port");

// get the type of the property `port`
Config.PropertyType propertyType = config.getType("port");

IO.println("type of `port`: " + type.name);
IO.println("value of `port`: " + port);
```

## TODOs
- a plugin system for defining custom property sources
- IDE/Maven support to flag missing properties and incorrect property types at
  compile time
- in-app notification of changed configuration sources
- APIs for other languages

## Design Choices

- Effectively immutable and atomic configuration.

## Documentation

TODO - see the sample `rwconfig` file in the project for more details
