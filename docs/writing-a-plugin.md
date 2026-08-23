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

A plugin is a jar that:

1. implements `SimpleConfigSourcePlugin` from the `plugin-api` artifact
2. declares itself as a service provider - in `module-info.java`, in
   `META-INF/services`, or both
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
        return false; // see `Change detection` below
    }

    @Override
    public void startChangeDetection() { }

    @Override
    public void stopChangeDetection() { }

    @Override
    public boolean isChanged() {
        return false;
    }
}
```

The library calls these in order: constructor, `getPluginVersion`, `setSourceName`,
`getRequiredPluginPropertyNames`, `getOptionalPluginPropertyNames`,
`setPluginProperties`, `isChangeDetectionSupported`, `startChangeDetection` if
`isChangeDetectionSupported` returned true, and then `getConfigSourceProperties` once. After that
`isChanged` is called on every polling cycle until the config is discarded, when
`stopChangeDetection` is called.

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
- **You do not have to implement `getPluginVersion`.** It defaults to the
version of the plugin API you built against. Override it if you want to use your
own version.
- **Change detection is optional.** If you return `false` from
`isChangeDetectionSupported`, then `startChangeDetection`, `isChanged`, and
`stopChangeDetection` are never called.

### 2. Declaring the service

Plugins are found with `ServiceLoader`, which looks in a different place
depending on how the application runs. Declaring both costs two files and makes
one jar work either way, which is what the bundled plugins do.

**On the module path**, in `module-info.java`:

```java
module com.example.config.plugin.consul {
    requires java.base;
    requires org.slf4j;
    requires net.rabbitware.config.plugin.api;

    provides net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
        with com.example.config.plugin.consul.ConsulPlugin;
}
```

**On the class path**, in a file named after the interface, containing the
implementing class:

```
src/main/resources/META-INF/services/net.rabbitware.config.plugin.api.SimpleConfigSourcePlugin
```

```
com.example.config.plugin.consul.ConsulPlugin
```

A `module-info.java` is ignored when its jar is on the class path, and
`META-INF/services` is ignored when it is on the module path - so the two never
collide, and a jar carrying both is discoverable from either.

Declare at least one. Without it the library will not find the class no matter
what the `rwconfig` file says, and the error will say the type is unknown rather
than that the jar is unregistered.

### 3. Naming, and how the library finds it

A type in the `rwconfig` file is resolved to a plugin by name - the module name
on the module path, the implementing class's package on the class path:

| in `rwconfig` | name the library looks for |
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

> **Name your module and your package the same.** A plugin is found on the
> module path by its module name, and on the class path by the package of the
> class that implements the plugin. Keeping the two identical means one plugin
> name works from either path. The bundled plugins all do this;
> `net.rabbitware.config.plugin.json` is both.

## Change detection

rwConfig polls; a plugin does not push. There is no listener to call and no
thread to start - the library asks `isChanged()` on its own schedule, and your
answer is a plain boolean:

```java
@Override
public boolean isChangeDetectionSupported() {
    return true;
}

@Override
public void startChangeDetection() throws Exception {
    lastSeen = fetchRevision();          // whatever "unchanged" means for you
}

@Override
public boolean isChanged() throws Exception {
    long current = fetchRevision();
    if (current == lastSeen) {
        return false;
    }
    lastSeen = current;                  // report a change once, not every poll
    return true;
}

@Override
public void stopChangeDetection() throws Exception {
    // release whatever `start` acquired
}
```

There are three things implementing change detection asks of you:

- **`isChanged` must report a change once.** It is called repeatedly. Returning
  `true` until someone rebuilds would fire an event on every cycle.
- **It runs on the polling thread, so keep it cheap.** It is called every five
  seconds by default, for every source. If checking means a network call, use
  something conditional - a `HEAD`, an ETag, a revision number - rather than
  fetching the whole document.
- **Throwing is allowed.** The library turns it into an error event and carries
  on polling; one unhappy source does not stop the others.

### Extending `LocationBasedConfigSourcePlugin` for document-based plugins 
**If your source is in a file, a jar, or a URL, you can get all of this for free.** Extend
`LocationBasedConfigSourcePlugin` instead of implementing the interface: it
takes the `location` property, validates it, watches it, and leaves you one
method to write.

```java
public class ConsulPlugin extends LocationBasedConfigSourcePlugin {
    @Override
    public Map<String, String> getConfigSourceProperties() throws Exception {
        String content = LocationBasedConfigSourcePlugin.loadResource(getLocation());
        return flatten(content);
    }
}
```

That is how all the bundled file-based plugins are written. It handles `file:`,
`jar:file:`, `classpath:`, `http:`, and `https:`, and reports change detection
as supported for exactly the ones that can be watched.

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
// reads file:, classpath:, jar:, http:, and https: locations, with a
// 10 second connect timeout and a 10 second read timeout
String content = SimpleConfigSourcePlugin.loadResource(location);

// true if the string starts with a prefix loadResource understands
boolean ok = SimpleConfigSourcePlugin.isSupportedLocation(location);
```

If your plugin takes a `location` property, use both - users then get the same
set of prefixes and the same failure behavior they get everywhere else. Check
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
- if you flatten hierarchical data, that arrays, empty containers, nulls, and
  mixed-type arrays all come out as intended

Then add one end-to-end test that runs a real `rwconfig` through
`ConfigFactory` with your plugin declared, which is the only way to check the
module declaration and naming are right.

## Checklist

- [ ] implements every method of `SimpleConfigSourcePlugin`
- [ ] declared as a service provider: `provides ... with ...` in
      `module-info.java`, `META-INF/services`, or both
- [ ] module named so the `rwconfig` file can reach it
- [ ] required properties listed, and checked in `setPluginProperties`
- [ ] optional properties handled when absent (they arrive as `null`)
- [ ] failures throw, with a message saying what was being attempted
- [ ] uses `loadResource` if it takes a location
- [ ] the module name and the implementing class's package are the same
- [ ] `META-INF/services` names the implementation, so the classpath can find
      it, too
- [ ] `isChangeDetectionSupported` either reports `false`, **or**:
  - [ ] `startChangeDetection` does any setup needed to start change detection
  - [ ] `isChanged` reports each change only once and is cheap enough to run
        every polling cycle
  - [ ] `stopChangeDetection` removes any resources allocation by
        `startChangeDetection`
