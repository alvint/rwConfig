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

rwConfig requires Java 21 or later.

Add the dependency to your project:

```xml
<dependency>
    <groupId>net.rabbitware.config</groupId>
    <artifactId>config</artifactId>
    <version>0.2.0</version>
</dependency>
```

## A first `rwconfig` file

Create a file named `rwconfig` in your working directory, or in
`src/main/resources`. The smallest one that works is a single line:

```
int port = 8080
```

That is a complete, valid configuration. It declares one property, `port`, of
type `int`, defaulting to `8080` - and with no config sources declared, the
default is simply the value.

Declarations get more precise from there:

```
int[80, 1024..65535] port = 8080
string greeting = Hello
dbPassword
```

- `port` may now only be `80` or anything from `1024` to `65535`. A value
  outside that fails at startup rather than at bind time.
- `greeting` is a `string` defaulting to `Hello`.
- `dbPassword` is a `string` (the default type if not specified) with **no
   default value**. Something has to provide it or the application will not
   start. That is deliberate - it is how you say "this must be configured,
   and I am not putting it in a shared file."

That last one needs somewhere for the value to come from, which is what config
sources are for. Add them above the declarations:

```
# Where to look for values, best source first.
rwc.sources = args, environment

rwc.args.type = commandLineArguments
rwc.environment.type = environmentVariables

# The properties this application uses.
int[80, 1024..65535] port = 8080
string greeting = Hello
dbPassword
```

Now `dbPassword` can be set with `dbPassword=secret` on the command line or the
environment variable `DB_PASSWORD`, and either will override a default.

Look here for more examples of `rwconfig` files:
- a [minimal `rwconfig`](../example/src/main/resources/rwconfig-minimal)
- and a [heavily commented one](../example/src/main/resources/rwconfig) exercising
  nearly every feature.

Lines starting with `#` or `!` are comments. Anything starting with `rwc.` is a
library setting - configuration *for the library itself* rather than a property
of your application - and it is entirely optional.

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
| `int[80, 1024..65535] port = 8080` | `port=99` | rejected: `99` is not an allowed value |
| `int port = 8080` | `port=eighty` | rejected: not an `int` |
| `dbPassword` (no default) | nothing | rejected: not set by any source, no default |

A source that can be read in full - a properties file, a directory, anything
loaded by a plugin - is also checked the other way round: a value in the source
that no property declares is an error, because it is almost always a typo.
Command line arguments are checked the same way, since every one of them was
typed deliberately for this program: `prot=9000` when you declared `port` stops
startup. When a source legitimately carries things your application does not
care about, say so for that source:

```
rwc.local.ignoreUnknownProperties = true
```

Environment variables and system properties are the exception. They are looked
up by name, one declared property at a time, so the hundreds of unrelated
entries in them are never seen - and neither would a typo be. See [Config
sources](config-sources.md#unknown-properties).

Mistakes in Java code are errors too. `config.getInt("prot")` throws
`PropertyNotFoundException`; `config.getInt("greeting")` throws
`IncorrectTypeException`. Both are unchecked, because both are bugs you fix
rather than conditions you handle.

## Catching mistakes before startup

Everything above is caught when the application runs. Two optional tools move
most of it earlier - to the moment you save a file, and to the build. Both are
front ends over the same rule set, so a finding reads the same wherever you meet
it.

Neither checks by loading a config source. Only the `rwconfig` file and your
Java sources are read, so the checks work with no network, no database, and no
secrets - which is the normal state of a developer's machine. Loading the
sources for real is a separate thing you ask for, described below.

### The VS Code extension

Install **rwConfig Support** from the Marketplace, or from a terminal:

```
code --install-extension rabbitware.rwconfig
```

There is nothing to set up per project. The extension activates when a workspace
contains an `rwconfig` file, and from then on it re-checks whenever you save a
`.java` or `rwconfig` file, underlining what it finds. It also highlights the
`rwconfig` file itself, which is worth having on its own.

The checks run in a Java process, so `java` has to be on your `PATH` - or name
one with the `rwconfig.javaPath` setting. Nothing else is needed; the analyzer
ships inside the extension.

`rwconfig.skipRules` takes rule ids to ignore, and `rwconfig.file` and
`rwconfig.sourceRoots` are there for a layout the extension does not work out on
its own. Two commands are in the palette: **rwConfig: Check code against the
rwconfig file**, and **rwConfig: Test config sources** - the exception to
everything above, since it loads every source exactly as the application would.
It opens the files, URLs, and databases the file describes, and tells you
whether the application would start *on this machine*. Run it when you want that
answer, never on a timer.

### The Maven plugin

Add it to the build, and that is the whole of it:

```xml
<plugin>
    <groupId>net.rabbitware.config</groupId>
    <artifactId>rwconfig-maven-plugin</artifactId>
    <version>0.2.0</version>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>
```

The `check` goal binds to `process-sources`, so `mvn compile` runs it before
anything is compiled - a misread property is more useful before the module is
built than after. It looks for `src/main/resources/rwconfig`, then `rwconfig`,
and a module that has neither is skipped silently, which makes this safe to put
in a parent pom. To try it once without editing anything:

```
mvn net.rabbitware.config:rwconfig-maven-plugin:0.2.0:check
```

`rwconfig.failOnError`, `rwconfig.reportUnread`, `rwconfig.skipRules`, and
`rwconfig.skip` decide what a finding does to the build.

### What gets caught

A property read that nothing declares, with the nearest declared name
suggested. A getter that does not match the declared type. A source named in
`rwc.sources` with no type, or missing something its type needs. An `rwc.`
setting rwConfig would silently ignore. Credentials that would go out
unencrypted. Change detection asked for with nothing to watch, or a listener
added to a `Config` that was not built with it. A property declared and never
read.

The full list of rules, with what each one catches and how severe it is, is in
[Checking your code against your
`rwconfig`](../rwconfig-maven-plugin/README.md).

## Where to go next

- **[The `rwconfig` file](config-file.md)** - every part of the file format:
  types, allowed values, ranges, escapes, and splitting long lines.
- **[Config sources](config-sources.md)** - the built-in source types, how
  precedence works, and how to keep secrets out of shared files.
- **[The Java API](java-api.md)** - everything on the `Config` object.
- **[Writing a plugin](writing-a-plugin.md)** - adding a source type of your
  own.
- **[Error messages](errors.md)** - what each startup error means.
- **[Checking your code](../rwconfig-maven-plugin/README.md)** - every rule
  the Maven plugin and the VS Code extension apply.
- **[Bundled plugins](../PLUGINS.md)** - YAML, JSON, XML, HOCON, JDBC, and prefix.

There is also a heavily commented [example `rwconfig`
file](../example/src/main/resources/rwconfig) that exercises nearly every
feature, and a [runnable example application](../example) that loads it.
