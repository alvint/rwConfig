# Writing a Plugin

A plugin is a config source type of your own - Consul, AWS Parameter Store, an
internal service, a file format the bundled plugins do not cover.

The contract is small: **given some settings from the `rwconfig` file, return a
`Map<String, String>` of property names to values.** Everything after that -
types, allowed values, precedence, validation - is the library's job.

The [prefix plugin](../plugin-prefix/src/main/java/net/rabbitware/config/plugin/prefix/PrefixPlugin.java)
exists as a worked example and is the shortest thing to read alongside this
page.

## The pieces

A plugin is a Java module that:

1. implements `SimpleConfigSourcePlugin` from the `plugin-api` artifact
2. declares itself as a service provider in its `module-info.java`
3. is named so the library can find it

### 1. The class

```java
package com.example.config.plugin.consul;

import java.util.Map;
import java.util.Set;
import net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin;

public class ConsulPlugin implements SimpleConfigSourcePlugin {

    private String sourceName;
    private String address;
    private String prefix;

    @Override
    public String getPluginVersion() {
        return "1.0.0";
    }

    @Override
    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    @Override
    public Set<String> getRequiredPluginPropertyNames() {
        return Set.of("address");
    }

    @Override
    public Set<String> getOptionalPluginPropertyNames() {
        return Set.of("prefix");
    }

    @Override
    public void setPluginProperties(Map<String, String> properties) throws Exception {
        address = properties.get("address");
        if (address == null) {
            throw new Exception("missing required property: address");
        }
        // an optional property is absent as a null, not a missing key
        prefix = properties.getOrDefault("prefix", "");
    }

    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        // do the actual work here and return name -> value
        return fetchFromConsul(address, prefix);
    }

    @Override
    public boolean isChangeDetectionSupported() {
        return false;
    }

    @Override
    public void addChangeListener(SimpleConfigSourcePlugin.ChangeListener listener) {
        // nothing to do while change detection is unsupported
    }
}
```

The library calls these in order: constructor, `getPluginVersion`,
`setSourceName`, `getRequiredPluginPropertyNames`,
`getOptionalPluginPropertyNames`, `setPluginProperties`, then
`getConfigSourceProperties` once.

A few things worth getting right:

- **Required properties are checked for you.** If the `rwconfig` file omits one
  the library reports it before your plugin is asked for anything. Checking
  again in `setPluginProperties`, as above, costs nothing and makes the plugin
  usable on its own.
- **Optional properties arrive as `null`** when they are not set, so use
  `getOrDefault` or a null check. The map always contains the key.
- **Throw on anything you cannot handle.** The library wraps your exception
  with the source name, so the user is told which source failed. Never return a
  partial map.
- **Change detection is not wired up yet.** Return `false` and leave
  `addChangeListener` empty, as the bundled plugins do.

### 2. The module declaration

```java
module com.example.config.plugin.consul {
    requires java.base;
    requires org.slf4j;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with com.example.config.plugin.consul.ConsulPlugin;
}
```

The `provides` clause is what makes the plugin discoverable. Without it the
library will not find the class no matter what the `rwconfig` file says.

### 3. Naming, and how the library finds it

Plugins are located **by module name**, through `ServiceLoader`. A type in the
`rwconfig` file is resolved one of two ways:

| in `rwconfig` | module the library looks for |
|---|---|
| `consul.plugin` | `net.rabbitware.config.plugin.consul` |
| `com.example.config.plugin.consul` | exactly that |

The short `<name>.plugin` form is a convenience for plugins living under the
library's own namespace. **For your own plugin, use the full module name:**

```
rwc.settings.type = com.example.config.plugin.consul
rwc.settings.address = http://consul.internal:8500
rwc.settings.prefix = myapp/
```

> **The application must run on the module path.** Plugins are found by module
> name, and a jar on the classpath is an unnamed module, so it will never
> match. If a plugin is not being found, this is the first thing to check.

## Returning properties

Return the names your users will declare in their `rwconfig` file. Flat sources
map straight across. For anything hierarchical, follow the convention the
bundled plugins use so your plugin feels the same as theirs:

- **A backslash separates levels.** `server` containing `port` becomes
  `server\port`.
- **Array elements use their index.** `accounts\0\name`, `accounts\1\name`.
- **A uniform array of primitives collapses into one comma-separated value**,
  so it can be read as a list type. `[1, 2, 3]` becomes `1,2,3`, readable as an
  `intList`. Mixed-type arrays fall back to indexed names.
- **Escape a literal backslash in a key** by doubling it, so a nested `a`/`b`
  (`a\b`) cannot collide with a flat key that really is called `a\b`
  (`a\\b`).

[PLUGINS.md](../PLUGINS.md) documents these rules from the user's side, with
examples.

Return values as strings and let the library parse them. Do not try to
interpret types yourself - the `rwconfig` file already says what each property
is, and your idea of "looks like a number" may not match it.

## Helpers you get for free

`SimpleConfigSourcePlugin` has two static methods worth using rather than
reimplementing:

```java
// reads file:, classpath:, jar:, http: and https: locations, with a
// 10 second connect timeout and a 10 second read timeout
String content = SimpleConfigSourcePlugin.loadResource(location);

// true if the string starts with a prefix loadResource understands
boolean ok = SimpleConfigSourcePlugin.isSupportedLocation(location);
```

If your plugin takes a `location` property, use both - users then get the same
set of prefixes and the same failure behaviour they get everywhere else. Check
`isSupportedLocation` in `setPluginProperties` so a bad location is reported
before any work is done.

The timeouts are `CONNECT_TIMEOUT_MILLIS` and `READ_TIMEOUT_MILLIS` on the same
interface, if you need to reference them.

## Testing

Drive the plugin directly rather than through `ConfigFactory` - it is faster
and a failure points at the plugin:

```java
@Test
void itReadsProperties() throws Exception {
    ConsulPlugin plugin = new ConsulPlugin();
    plugin.setSourceName("settings");
    plugin.setPluginProperties(Map.of("address", server.url()));

    Map<String, String> properties = plugin.getConfigSourceProperties();

    assertEquals("8080", properties.get("server\\port"));
}
```

The bundled plugins' tests are laid out this way - see
[`JsonPluginTest`](../plugin-json/src/test/java/net/rabbitware/config/plugin/json/JsonPluginTest.java)
for a fuller one. Cases worth covering:

- a missing required property is rejected
- an optional property left out uses its default and does not fail
- an unreachable or malformed source throws rather than returning a partial map
- if you flatten hierarchical data, that arrays, empty containers, nulls and
  mixed-type arrays all come out as intended

Then add one end-to-end test that runs a real `rwconfig` through
`ConfigFactory` with your plugin declared, which is the only way to check the
module declaration and naming are right.

## Checklist

- [ ] implements every method of `SimpleConfigSourcePlugin`
- [ ] `provides ... with ...` in `module-info.java`
- [ ] module named so the `rwconfig` file can reach it
- [ ] required properties listed, and checked in `setPluginProperties`
- [ ] optional properties handled when absent (they arrive as `null`)
- [ ] failures throw, with a message saying what was being attempted
- [ ] uses `loadResource` if it takes a location
- [ ] the application runs on the module path
