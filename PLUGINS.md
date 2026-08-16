# Plugins
These plugins are currently available and are built with the project.

| plugin | type | artifact | brings in | required properties |
|---|---|---|---|---|
| [JSON](#json-jsonplugin) | `json.plugin` | `plugin-json` | `org.json:json` | `location` |
| [YAML](#yaml-yamlplugin) | `yaml.plugin` | `plugin-yaml` | `org.snakeyaml:snakeyaml-engine` | `location` |
| [XML](#xml-xmlplugin) | `xml.plugin` | `plugin-xml` | `org.json:json` | `location` |
| [JDBC](#jdbc-jdbcplugin) | `jdbc.plugin` | `plugin-jdbc` | nothing - you supply the driver | `connectionString`, `query` |
| [Prefix](#prefix-prefixplugin) | `prefix.plugin` | `plugin-prefix` | nothing | `mediaType`, `location` |

All artifacts are in the `net.rabbitware.config` group.

> **A plugin is a separate jar, and the `.plugin` suffix in the type is there to
> remind you.** Each one has to be added to your build, and unlike rwConfig
> itself - which depends on nothing but the Java Base module and slf4j - a
> plugin may bring dependencies of its own. The "brings in" column is what each
> costs you.

> **Plugins must be on the module path.** They are located with `ServiceLoader`
> and matched by module name, so a plugin jar on the classpath will not be
> found--you'll get `no plugin provides config source type ...`. The plugin jars
> declare their service in `module-info` and carry no `META-INF/services`
> fallback, so the classpath has nothing to find them by. The error lists the
> plugins it did find, which tells you whether the jar is missing entirely or
> merely on the wrong path.

## Using a Plugin
A plugin is just another config source. Name it in `rwc.sources`, give it
a `type`, and set whatever properties that type requires:

```
rwc.sources = jsonFile
rwc.jsonFile.type = json.plugin
rwc.jsonFile.location = classpath:app.json
rwc.jsonFile.ignoreUnknownProperties = true
```

The type is the plugin's short name followed by `.plugin`. You can also give the
plugin's full module name (`net.rabbitware.config.plugin.json`) if you prefer.

`ignoreUnknownProperties` isn't specific to plugins, but it matters more here
than elsewhere: JSON, YAML, and XML sources usually contain far more properties
than your app declares, and without it those undeclared properties are an error
at startup. See [Config Sources](docs/config-sources.md) for the details.

## Common Properties
Several plugins read from a location, and they all accept it the same way.

- `location`
  - The prefixed location of the config source. Supported location
    prefixes are `file:`, `classpath:`, `jar:`, `http:`, and `https:`.
    - `file:` prefixes can be relative or absolute. Relative prefixes resolve
      from the current working directory. For example, `file:my/file` is ok.
    - `jar:` URL syntax is normally `jar:file:/path/to/jar!path/to/item`.

## How Nested Formats Are Flattened
JSON, YAML, and XML all describe a tree, and rwConfig properties are flat. The
three plugins therefore share one set of rules, described here once. Each plugin
section below only covers what is specific to its format.

**A property's name is the path to its value.** Start at the top of the
document, write down each key you pass through on the way to a value, and join
them with backslashes. Inside an array, the position is used instead of a key -
`0`, `1`, `2`. Only values become properties; objects and arrays are the
structure you walk through, not values in their own right.

```json
{
  "numberOfAccounts": 2,        ->  numberOfAccounts = 2
  "accounts": [                      (structure, not a value)
    {
      "name": "alvin",          ->  accounts\0\name = alvin
      "role": "admin"           ->  accounts\0\role = admin
    },
    {
      "name": "carl",           ->  accounts\1\name = carl
      "role": "user"            ->  accounts\1\role = user
    }
  ]
}
```

Note that `accounts` is not itself a property. Nothing is lost - every value it
contains is reachable - but there is no property whose value is "the accounts
array".

### Why a backslash
Keys in many nested formats (JSON, YAML, etc.)  may legally contain dots, and
often do. So `a.b` is a perfectly good name for a single property. That makes a
dot useless as a separator; `a.b` would be ambiguous between one name and two
levels.

While a backslash is technically legal in keys for some nested formats, it is
much more rarely used. Combine this with the rules under [Keys that would
collide](#keys-that-would-collide) and the library can now ingest any nested
format and keep property names unambiguous.

### Lists
There is one exception to the path rule. If an array holds a homogenous list of
booleans, strings, floating-point numbers, or integers, the whole array becomes
a single comma-separated value on the key that holds it, rather than one
property per index. That is what makes it readable by a `stringList`, `intList`,
and the other list types.

If the array holds anything else - objects, nested arrays, or a mixture of
scalar types - the ordinary path rule applies and you get one indexed property
per element.

```json
{
  "strings": ["a", "b", "c"],   ->  strings = a,b,c
  "ints": [1, 2, 3, 4, 5],      ->  ints = 1,2,3,4,5
  "floats": [1.5, 2.5, 3.5],    ->  floats = 1.5,2.5,3.5
  "mixed": [1, 2.5, 3]          ->  mixed\0 = 1
}                                   mixed\1 = 2.5
                                    mixed\2 = 3
```

`mixed` falls back because it mixes integers and floating-point numbers, which
is not a type any rwConfig list can hold.

An empty array counts as homogenous, and its value is the empty string. That
works out because a property declared with any list type reads an empty string
as an empty list.

### Nulls and empty values
A null becomes the string `"null"`. If the property is declared in your
`rwconfig` file as anything but a `string`, that fails at startup rather than
silently becoming `0` or `false` - which is the intent.

An empty value is a different thing from a null, and stays an empty string.

### Keys that would collide
Because a backslash separates levels, a key that itself contains a backslash
would be ambiguous. Consider:

```json
{
  "a": { "b": "wazoo" },
  "a\\b": "what happens here?"
}
```

The second key is a literal `a\b` once the parser has read the escape, and
without a rule it would name the same property as the nested `a` -> `b`. So
before flattening, keys are rewritten:

1. A literal backslash in a key is escaped with another backslash.
1. An empty key - legal in JSON and YAML - is renamed to `empty\key`.
1. A null key - legal in YAML - is renamed to `null\key`.

which keeps the two apart:

- `a\b=wazoo` - the nested value
- `a\\b=what happens here?` - the key that contained a backslash

These rules affect very few real documents; empty keys, null keys, and keys with
backslashes are all rare. Where they do apply, no data is lost - the value is
simply reachable under the adjusted name.

## JSON (`json.plugin`)
Loads properties from a JSON file.
### Required Properties
- `location`
  - Where to read the source from. See
    [Common Properties](#common-properties).
### Details
JSON is flattened exactly as described in
[How Nested Formats Are Flattened](#how-nested-formats-are-flattened), and adds
nothing of its own.

## YAML (`yaml.plugin`)
Loads properties from a YAML file.
### Required Properties
- `location`
  - Where to read the source from. See
    [Common Properties](#common-properties).
### Optional Properties
- `resolveMergeKeys` (default is `true`)
  - YAML version 1.2 removed merge keys from the specification, although many
    parsers still support it. There's a catch: with merge keys removed from
    the 1.2 spec, the spec is unambiguous that the `<<` symbol must be treated
    like any other key. That means that the `<<` key is added to the node
    hierarchy instead of being transparent. I find this behavior annoying so
    this property (on by default) removes these stray `<<` keys from the
    hierarchy.

    The catch is with this hack on, `<<` is no longer a valid node name. If you
    want to adhere more closely to the 1.2 spec then you can turn this off.

    See below for more details.
### Details
YAML is flattened as described in
[How Nested Formats Are Flattened](#how-nested-formats-are-flattened), with
sequences playing the part of arrays:

```yaml
numberOfAccounts: 2             ->  numberOfAccounts = 2
accounts:
  - name: alvin                 ->  accounts\0\name = alvin
    role: admin                 ->  accounts\0\role = admin
  - name: carl                  ->  accounts\1\name = carl
    role: user                  ->  accounts\1\role = user
```

Merge keys are the one thing YAML adds.

### Merge Keys
> Note that YAML's `<<` is unrelated to the `<<` used as a *deferred value* in
> library settings. This one is YAML's own merge key, and only has meaning
> inside a YAML document.

Merge keys have been popular and useful since YAML 1.1. It allows you to insert
the properties of one object into another object. For example:
```yaml
defaults: &defaults
  retries: 3
  timeout-seconds: 10
mergeKeyTest:
  <<: *defaults
  queue: jobs
```
In this YAML, the properties from `defaults` are also added to `mergeKeyTest`.

However, there's a catch as of YAML version 1.2. The spec is pretty clear that
the `<<` key must be added to the node graph, even if in this case it's just a
stand-in for the properties in `defaults`. If you stick faithfully to the 1.2
spec, this yields the following properties for `mergeKeyTest`:
- `mergeKeyTest\queue=jobs`
- `mergeKeyTest\<<\retries=3`
- `mergeKeyTest\<<\timeout-seconds=10`

I found these extra `<<` keys annoying, so if the optional plugin property
`resolveMergeKeys` is set to `true` (the default), the merge keys will be
removed from the object graph. This yields more pleasant keys:
- `mergeKeyTest\queue=jobs`
- `mergeKeyTest\retries=3`
- `mergeKeyTest\timeout-seconds=10`

There is a caveat: now `<<` is not a valid key name. If you want to stick more
closely to the YAML version 1.2 spec you can turn this option off.

## XML (`xml.plugin`)
Loads properties from an XML file.
### Required Properties
- `location`
  - Where to read the source from. See
    [Common Properties](#common-properties).
### Details
XML is flattened as described in
[How Nested Formats Are Flattened](#how-nested-formats-are-flattened). Two
things are specific to XML.

**Attributes become properties, in the same namespace as elements.** An
attribute `a` and a child element `a` of the same parent therefore name the same
property. Nothing is dropped - they are treated as repeated values and collapse
into a list, the same as any repeated scalar:

```xml
<root a="fromAttribute"><a>fromElement</a></root>   ->  root\a = fromAttribute,fromElement
```

**Repeated elements are an array.** If an element contains several children with
the same name, they are treated as a list under that shared name - which then
follows the [list rules](#lists), collapsing to a comma-separated value when the
values are homogenous.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">   ->  project\xmlns = http://maven.apache.org/POM/4.0.0
    <groupId>com.foo</groupId>                        ->  project\groupId = com.foo
    <artifactId>example</artifactId>                  ->  project\artifactId = example
    <dependencies>
        <dependency>
            <groupId>org.slf4j</groupId>              ->  project\dependencies\dependency\0\groupId = org.slf4j
            <artifactId>slf4j-api</artifactId>        ->  project\dependencies\dependency\0\artifactId = slf4j-api
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>              ->  project\dependencies\dependency\1\groupId = org.slf4j
            <artifactId>slf4j-simple</artifactId>     ->  project\dependencies\dependency\1\artifactId = slf4j-simple
        </dependency>
    </dependencies>
</project>
```

The two `dependency` elements are objects rather than scalars, so they keep
their indexes. Repeated *scalar* elements collapse instead:

```xml
<foo>
  <ints><val>1</val><val>3</val><val>5</val></ints>       ->  foo\ints\val = 1,3,5
  <mixed><val>1</val><val>2.5</val><val>3</val></mixed>   ->  foo\mixed\val\0 = 1
</foo>                                                        foo\mixed\val\1 = 2.5
                                                              foo\mixed\val\2 = 3
```

XML has no null literal, so the null rule never fires in practice. An empty
element such as `<a></a>` is an empty value, not a missing one.

## JDBC (`jdbc.plugin`)
This plugin loads properties from a database. This plugin also requires that the
JDBC driver of the database you're trying to connect to is on your classpath.
### Required Properties
- `connectionString`
  - The string used by the JDBC driver to connect to the database.
- `query`
  - The query used to retrieve the properties for your application. This query
    must return at least two columns. The first column must be the property name
    and the second column must be the property value.
### Optional Properties
- `username`
  - The username used to connect to the database.
- `password`
  - The password used to connect to the database. It is recommended to **not**
    put the password for your database directly in this file. Instead, set this
    to a *deferred value* (`<<`) and place the real value in a config source
    that gets loaded before this one. See
    [Config Sources](docs/config-sources.md#keeping-secrets-out-of-shared-files)
    for details.
### Details
The plugin works as you would expect. It executes the supplied query and gathers
the results to be used as properties.

## Prefix (`prefix.plugin`)
This is an example plugin which prefixes the source name to all properties that it
loads.
### Required Properties
- `mediaType`
  - currently supports `properties`
- `location`
  - Where to read the source from. See
    [Common Properties](#common-properties).
### Details
The plugin currently derives the prefix from the source name, but this may change
to a required property.
