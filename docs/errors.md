# Error Messages

Nearly every error happens during `ConfigFactory.create`, before your code
runs. That is the design: a configuration problem should stop the application
starting rather than surface later as a strange value.

Each message below is what you will see, followed by what causes it.

## The `rwconfig` file itself

**`config file path is not a valid location: <path>`**

No `rwconfig` file was found. The library looks at `rw.config.path` (command
line argument, then system property), then `RW_CONFIG_PATH`, then the
classpath, then the working directory. If you set a path explicitly, check the
prefix - it takes a [location](config-sources.md#locations) such as
`file:/etc/myapp/rwconfig`, not a bare path.

**`invalid config line: <line>`**

The line does not fit the format. Common causes:

- a space in a property name (`first name = Joe`)
- a name starting with a digit or a period (`1stName`, `.rc`)
- an empty allowed values list (`string[] myProp`)
- a malformed range - too many separators, or a missing bound (`[0..100..]`,
  `[..100]`, `[100..]`)
- a blank allowed value for a non-string type (`intList[1,,10]`)
- an escape sequence that is not recognized *inside an allowed values list*
  (`string[a\qb] x`)

**`duplicate config line for property: <name>`**

The same property is declared twice.

**`invalid config line (missing value): <line>`**

A `rwc.` line has no value. Library settings always need one - `rwc.prefix` on
its own is not meaningful.

**`invalid config line (library settings cannot have a type): <line>`**
**`invalid config line (library settings cannot have allowed values): <line>`**

A `rwc.` line was given a type or an allowed values list. Those settings are
always strings.

## Escape sequences

**``invalid escape sequence `\q` in value: <value>``**

A backslash followed by something that is not an escape. The valid ones are
`\\`, `\e`, `\n`, `\r`, `\t`, `\uXXXX`, and a leading `\ `; allowed values also
take `\]`, `\,` and `\:`. A `[` is never escaped - write it as-is.

This also appears for a malformed unicode escape, which needs exactly four hex
digits: `\u12` is reported as ``invalid escape sequence `\u` ``.

**`invalid ending backslash in value: <value>`**

The value ends with a lone backslash. Remember a trailing backslash joins the
line with the next one, so this usually means the last line of the file ends
with one, or a line ends with `\\` and the join left a stray backslash behind.

**Warning: `an escaped space is only meaningful at the start of a value...`**

Not an error. `\ ` only does something as the first non-whitespace character of
a value, where it protects a leading space. Anywhere else a space needs no
escaping, so this is almost always a mistake. The value is used with the escape
treated as a plain space.

## Values and types

**``error parsing the value of `<name>` (in <source>) as type `<type>`: <value>``**

The value is not that type - `int myProp = 50.5`, or a source supplying
`port=eighty`. The message names where the value came from, so you know where to
look: `<source>` reads either ``source `<name>` `` for a declared config source,
or ``the `rwconfig` file`` when the value was a default written in the file
itself.

For a list type this also appears when an item is blank. Only `stringList`
allows blank items; `intList x = 1,,2` fails on the empty one.

**``value is not allowed for property `<name>` (in <source>): <value>``**

The value is not in the property's allowed values. Check whether the property
should accept it, rather than reflexively widening the list.

**``invalid allowed value range for property `<name>`: <range>``**

A range with more than one separator. A range is `<min>..<max>`; to use a
literal `..` inside a value, escape it as `\.\.`.

**``property `<name>` is not set by any config source, and has no default value defined in the `rwconfig` file``**

A property was declared without a default value and nothing supplied one. Either
give it a default value or set its value in a source. This is the intended way
to require configuration - a missing API key stops startup instead of causing a
confusing failure later.

**``property `<name>` is not defined in the `rwconfig` file, and config source `<source>` does not allow unknown properties``**

A source contains a property that was not declared in the `.rwconfig` file -
usually a typo in the source or a property you forgot to declare. If the source
legitimately carries extra things, set
`rwc.<source>.ignoreUnknownProperties = true`.

## Config sources

**``missing library setting for config source `<name>`: rwc.<source>.<property>``**

A source is missing something it needs - every source needs `type`, a
`properties` source needs `location`, a `directory` source needs `path`. See
[Config sources](config-sources.md) for what each type takes.

**`duplicate config source name: <name>`**

The same name appears twice in `rwc.sources`.

**``error loading properties from location for config source `<name>`: <location>``**

The file could not be read. Check the path and the prefix. For `http:` and
`https:` this also covers a connection that timed out - both timeouts are ten
seconds.

**``error getting properties for config source `<name>` of type `<type>` ``**

The source was reached but failed while producing properties - a database query
against a missing table, malformed JSON, unreadable YAML. The cause is chained;
look at the exception below this one for the real reason.

**``no plugin provides config source type `<type>` ``**

A plugin could not be found. The message names the Maven artifact to add and
lists the plugins it did find, which distinguishes the usual causes:

- **`plugins found: (none)`** - no plugin jar is present. A plugin must be on
  either the module path or the class path. A plugin is found on the module path
  by its module name, and on the class path by the package of its implementing
  class.
- **other plugins are listed** - the mechanism works, so this jar in particular
  is missing, or the type name is wrong. `yaml.plugin` resolves to the module
  `net.rabbitware.config.plugin.yaml`; a plugin of your own is named by its full
  module name.

See [Plugins](../PLUGINS.md) for the artifact each type needs.

**``library setting `rwc.<source>.<property>` is set to `<<`, but no config source loaded before `<name>` contains this property``**

A *deferred value* did not resolve. The source holding the real value has to
appear **earlier** in `rwc.sources` than the one referring to it, and the
property name it provides must match exactly - including the `rwc.` prefix.

## Errors from your own code

These come from reading values, not from startup, and both mean the Java code
and the `rwconfig` file disagree.

**``property `<name>` not found``** - `PropertyNotFoundException`. The name is
not declared. Usually a typo in the Java code.

**``property `<name>` is of type `<actual>` - not type `<requested>``** -
`IncorrectTypeException`. Wrong getter: `getInt` on a string, `getIntList` on a
scalar, `getStringList` on an `intList`. Use `config.getType(name)` if you need
to check at runtime.

Both are unchecked, because both are bugs to fix rather than conditions to
handle.

## Change detection

### `change detection is not enabled for this Config object`

A change listener was registered on a `Config` built without change detection.
It is refused rather than accepted, because a listener that can never fire is a
bug that would otherwise show up as silence:

```java
Config config = ConfigFactory.create(true, args);   // `true` turns it on
```

### `a change detection polling interval was set in the rwconfig file but the Config object was not created with change detection support`

A warning, not an error. `rwc.changeDetectionPollingInterval` is set but nothing
is polling, so the setting has no effect. Either pass `true` to
`ConfigFactory.create`, or remove the line.

### `error starting change detection for config source ...`

A source that reports it can be watched failed to start watching - a file whose
directory is not readable, or a URL that could not be reached. This stops
startup, on the grounds that a source you asked to watch and cannot is a
configuration problem rather than something to discover later.

### `unable to stop change detection for config source ...`

Logged, not thrown, while a `Config` is being discarded. The discard finishes
either way. It means a source could not release whatever it was watching, which
generally means it had already gone.

## `.env` files

### `line N is not a comment or a \`KEY=value\` assignment`

A line in a [`dotenv`](config-sources.md#dotenv) source is none of the three
things a line may be. The line number is the file's, counting from one.

### `the value starting on line N opens with " and is never closed`

A quoted value has no closing quote anywhere in the rest of the file. Reported
rather than truncated at the end of the line, because a value that silently
loses its tail is worse than one that fails.

### `line N has text after the closing "`

Something follows a quoted value that is not a comment. Either it belongs inside
the quotes, or it needs a `#`.
