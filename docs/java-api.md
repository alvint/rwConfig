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

Use the `args` form if your `rwconfig` declares a `commandLineArguments`
source; it is an error to declare that source and then call `create()`.

Everything happens here: the file is read, the sources are loaded, every value
is parsed and checked against its type and allowed values, and any property
without a value is reported. If `create` returns, the configuration is complete
and valid.

Create it once at startup and hold on to it. The result is effectively
immutable, so it can be shared across threads freely, and there is no reload -
if configuration changes, build a new `Config`.

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
