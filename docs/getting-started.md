# Getting Started

rwConfig collects your application's configuration from wherever it happens to
live - files, environment variables, command line arguments, a database - and
hands it to your code as a small, typed, read-only object.

The idea it is built around: **your application declares what configuration it
needs in one file, and that file is the only place that knowledge lives.** Not
scattered through the code as string keys with `orElse` fallbacks, not split
between a properties file and a class full of constants. One file says what
properties exist, what type each one is, what values are acceptable, what the
defaults are, and where to look for real values.

Two things follow from that, and they are the point of the library:

- **Configuration problems are found at startup, not at 3am.** A missing
  property, a typo, a value of the wrong type, a value outside the allowed
  range - all of it fails when the application starts, with a message naming
  the property.
- **Reading a value is trivial.** `config.getInt("port")` returns an `int`. No
  `Optional`, no casting, no default buried in the call. The library already
  knows the type and already checked the value.

Three terms are used throughout these docs: the **`rwconfig` file** is where you
**declare** your properties; a **config source** is a place values come from,
listed in `rwc.sources`; and a **library setting** is an `rwc.` line that
configures rwConfig itself rather than declaring anything. Declarations and
library settings live side by side in the same file, so it is worth knowing
which is which.

## Install

rwConfig isn't on Maven Central yet - that's waiting on a version stable enough
to call `1.0.0`. Until then, clone the repo and install it into your local Maven
repository:

```
git clone https://github.com/alvint/config.git
cd config
mvn install
```

Then add the dependency to your project:

```xml
<dependency>
    <groupId>net.rabbitware.config</groupId>
    <artifactId>config</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## A first `rwconfig` file

Create a file named `rwconfig` in your working directory, or in
`src/main/resources`:

```
# Where to look for values, best source first.
rwc.sources = args, environment

rwc.args.type = commandLineArguments
rwc.environment.type = environmentVariables

# The properties this application uses.
int[80, 1024:65535] port = 8080
string greeting = Hello
dbPassword
```

That declares three properties:

- `port` is an `int`. It may be `80` or anything from `1024` to `65535`. If
  nothing sets it, it is `8080`.
- `greeting` is a `string` defaulting to `Hello`. No type is written, and
  `string` is the default type.
- `dbPassword` is a `string` with **no default**, so one of the sources has to
  provide it or the application will not start. That is deliberate - it is how
  you say "this must be configured, and I am not putting it in a shared file."

Lines starting with `#` or `!` are comments. Anything starting with `rwc.` is
configuration *for the library itself* rather than a property of your app.

## Reading values

```java
import net.rabbitware.config.*;

public class Main {
    public static void main(String[] args) {
        Config config = ConfigFactory.create(args);

        int port = config.getInt("port");
        String greeting = config.getString("greeting");
        String password = config.getString("dbPassword");

        System.out.println(greeting + ", starting on port " + port);
    }
}
```

Pass `args` to `create` if you want command line arguments to be usable as a
source, as the file above does. If you do not, use `ConfigFactory.create()`.

Run it:

```
java -jar myapp.jar port=9000
```

`port=9000` on the command line beats the default in the file, because `args`
is a declared source. `dbPassword` comes from the environment - either as
`dbPassword` or as the normalized `DB_PASSWORD`.

## What happens when something is wrong

Everything below is an error at startup, before your code runs:

| the file says | you supply | what happens |
|---|---|---|
| `int[80, 1024:65535] port = 8080` | `port=99` | rejected: `99` is not an allowed value |
| `int port = 8080` | `port=eighty` | rejected: not an `int` |
| `dbPassword` (no default) | nothing | rejected: not set by any source, no default |

A source that can be read in full - a properties file, a directory, anything
loaded by a plugin - is also checked the other way round: a value in the source
that no property declares is an error, because it is almost always a typo. When
a source legitimately carries things your application does not care about, say
so for that source:

```
rwc.local.ignoreUnknownProperties = true
```

This does not apply to command line arguments, environment variables or system
properties. Those are looked up by name, one declared property at a time, so
extra values in them are never seen. See [Config
sources](config-sources.md#unknown-properties).

Mistakes in Java code are errors too. `config.getInt("prot")` throws
`PropertyNotFoundException`; `config.getInt("greeting")` throws
`IncorrectTypeException`. Both are unchecked, because both are bugs you fix
rather than conditions you handle.

## Where to go next

- **[The `rwconfig` file](config-file.md)** - every part of the file format:
  types, allowed values, ranges, escapes, and splitting long lines.
- **[Config sources](config-sources.md)** - the built-in source types, how
  precedence works, and how to keep secrets out of shared files.
- **[The Java API](java-api.md)** - everything on the `Config` object.
- **[Writing a plugin](writing-a-plugin.md)** - adding a source type of your
  own.
- **[Error messages](errors.md)** - what each startup error means.
- **[Bundled plugins](../PLUGINS.md)** - YAML, JSON, XML, JDBC and prefix.

There is also a heavily commented [example `rwconfig`
file](../example/src/main/resources/rwconfig) that exercises nearly every
feature, and a [runnable example application](../example) that loads it.
