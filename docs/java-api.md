# The Java API

The whole API is one factory and one interface. There is no builder, no
annotation processing, and nothing to configure in code - all of that lives in
the [`rwconfig` file](config-file.md).

## Creating the config

```java
import net.rabbitware.config.*;

Config config = ConfigFactory.create(args);   // command line arguments usable
Config config = ConfigFactory.create();       // not
```

Use the `args` parameter if your `rwconfig` declares a `commandLineArguments`
source; it is an error to declare that source and then call a version of
`create()` that does not provide command line arguments.

Everything happens here: the file is read, the sources are loaded, every value
is parsed and checked against its type and allowed values, and any property
without a value is reported. If `create` returns, the configuration is complete
and valid.

Create it once at startup and hold on to it. The result is effectively
immutable, so it can be shared across threads freely. A `Config` is a snapshot
and stays one: if configuration changes, build a new one.

Speaking of changes, rwConfig can tell you when your config has changed if you
set the `enableChangeDetection` flag to `true`:
```java
Config config = ConfigFactory.create(true, args);
```

It is up to you to reload your config when you decide that has become worth
doing - see 
[Noticing that a source has changed](#noticing-that-a-source-has-changed).

There is also an optional `name` parameter, which is what appears in log
messages and in change events. It is worth setting when an application builds
more than one `Config` object. If you don't set your own name, the default name
is a random UUID:

```java
Config config = ConfigFactory.create("app config", true, args);
```
## Reading values

One method per type, each returning a primitive or a `List`:

```java
boolean debug   = config.getBoolean("debugMode");
int     port    = config.getInt("port");
long    maxSize = config.getLong("maxUploadSize");
double  rate    = config.getDouble("samplingRate");
String  name    = config.getString("appName");

List<Boolean> flags   = config.getBooleanList("featureFlags");
List<Integer> ports   = config.getIntList("workerPorts");
List<Long>    ids     = config.getLongList("accountIds");
List<Double>  weights = config.getDoubleList("weights");
List<String>  hosts   = config.getStringList("hosts");
```

No `Optional`, no default parameter, no cast. The type and the default were
settled in the `rwconfig` file, and the value was validated at startup.

Every method has a short alias, if you prefer terse code at the call site:

| long | short | | long | short |
|---|---|---|---|---|
| `getBoolean` | `getb` | | `getBooleanList` | `getbl` |
| `getInt` | `geti` | | `getIntList` | `getil` |
| `getLong` | `getl` | | `getLongList` | `getll` |
| `getDouble` | `getd` | | `getDoubleList` | `getdl` |
| `getString` | `gets` | | `getStringList` | `getsl` |

Returned lists are unmodifiable.

Reads are fast enough not to think about - a read is a single hash lookup and a
field access, and returning primitives avoids boxing. There is no reason to
copy values into fields "for speed", though there are plenty of good reasons to
copy them into well-named ones.

## Asking about properties

```java
boolean exists = config.has("port");                  // is it declared?
Config.PropertyType type = config.getType("port");    // INT
Set<String> names = config.getPropertyNames();        // every declared name
```

`getPropertyNames()` returns an unmodifiable, alphabetically sorted set. It
contains only your application's properties - library settings
itself are never included.

`PropertyType` is an enum: `BOOLEAN`, `INT`, `LONG`, `DOUBLE`, `STRING`,
`BOOLEAN_LIST`, `INT_LIST`, `LONG_LIST`, `DOUBLE_LIST`, `STRING_LIST`. Each has
a `name` field holding the spelling used in the `rwconfig` file (`intList` and
so on).

**These are the run-time types, which is not quite the list of types you can
write in the file.** `duration`, `size`, `timestamp`, and their list forms are parsed into
longs. For example, a property *declared* as `duration timeout` is *read* with
`var duration = config.getLong("duration")`.

Iterating everything, which is what the [example
application](../example/src/main/java/net/rabbitware/config/example/Test.java)
does:

```java
for (String name : config.getPropertyNames()) {
    System.out.println(name + " (" + config.getType(name).name + ")");
}
```

## Noticing that a source has changed

Change detection is off unless you ask for it, because it starts a thread:

```java
Config config = ConfigFactory.create(true, args);   // `true` turns it on
```

Then register a listener. It is called when a watched source changes - not when
a particular property changes, since rwConfig does not re-read the source for
you:

```java
config.addChangeListener("reloader", new Config.ChangeListener() {
    @Override
    public void onChange(Config.ChangeEvent event) {
        logger.info("config source `{}` changed at {}", event.source(), event.timestamp());
        Config replacement = ConfigFactory.create(true, args);
        Config.Instance.replace(replacement);       // discards the old one for you
    }

    @Override
    public void onError(Config.ErrorEvent event) {
        logger.warn("could not check `{}` for changes", event.source(), event.exception());
    }
});
```

`addChangeListener(sourceName, listenerName, listener)` narrows a listener to a
single source, and `removeChangeListener` takes it off again.

Asking for a listener on a `Config` built without change detection is an error
rather than a listener that can never fire.

### What takes part

A source can be watched only if its location can be:

| source | watched |
| --- | --- |
| a `file:` location | yes, through the filesystem's watch service |
| a `jar:file:` location | yes - the jar itself is watched |
| an `http:` or `https:` location | yes, by polling `Last-Modified` |
| a `classpath:` location | no - it cannot change while the JVM runs |
| a `jdbc.plugin` source | only if it declares a `changeQuery` |
| `environmentVariables`, `systemProperties`, `commandLineArguments` | no - fixed at startup |

Sources that cannot be watched are simply never reported as changed. Nothing
fails.

### The polling interval

Sources are checked every five seconds by default. To change it, set the
library setting in the `rwconfig` file:

```
rwc.changeDetectionPollingInterval = 30000
```

The value is milliseconds and must be positive. Setting it without asking for
change detection logs a warning, since nothing would use it.

Detection is not instant, and cannot be: on top of this interval, a filesystem
watch has a latency of its own - on macOS the JDK polls the filesystem, which
adds a second or so.

### Discarding a config you are finished with

A `Config` with change detection running holds a polling thread, and the library
holds the `Config`. Neither is collected while that is true, so a config you
have replaced has to be told it is finished:

```java
config.discard();
```

After that it stops watching and stops firing events. Its values stay readable,
so anything still holding it keeps working - `discard` retires the watching, not
the configuration.

`Config.Instance.replace(...)` calls `discard()` on the instance it swaps out,
so the common case is handled for you.

<!-- deliberate: this is the one place the library needs something back from -->
<!-- the caller, and the failure mode is a silent leak rather than an error. -->
> **Forgetting `discard` leaks.** A `Config` built with change detection and
> never discarded keeps its thread and its memory for the life of the process.
> If you never replace your configuration, you never need to call it.

## Errors

All of them extend `ConfigException`, which extends `RuntimeException`. None
are checked, on the grounds that they are bugs to fix rather than conditions to
recover from - there is nothing sensible to do at runtime about asking for a
property that does not exist.

| exception | when |
|---|---|
| `PropertyNotFoundException` | the name is not declared in the `rwconfig` file |
| `IncorrectTypeException` | the property exists but is a different type |
| `ConfigException` | anything wrong during `create` |

```java
config.getInt("prot");        // PropertyNotFoundException - typo
config.getInt("appName");     // IncorrectTypeException - it is a string
config.getIntList("port");    // IncorrectTypeException - it is a scalar
```

The first two can only happen because the Java code and the `rwconfig` file
disagree, which is why they are unchecked: the fix is to change one of them,
not to catch anything.

`ConfigException` from `create` is different - it means the *configuration* is
wrong, not the code, and its message names the property or source at fault. See
[Error messages](errors.md) for a guide to them.

## A typical application

```java
public class Main {
    public static void main(String[] args) {
        Config config = ConfigFactory.create(args);

        var server = new Server(
            config.getInt("port"),
            config.getInt("workerThreads"),
            config.getStringList("allowedOrigins")
        );

        if (config.getBoolean("debugMode")) {
            server.enableDebugEndpoints();
        }
        server.start();
    }
}
```

Read what you need where you need it and pass values on as ordinary arguments.
