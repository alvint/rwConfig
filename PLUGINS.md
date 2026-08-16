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

## JSON (`json.plugin`)
Loads properties from a JSON file.
### Required Properties
- `location`
  - Where to read the source from. See
    [Common Properties](#common-properties).
### Details
The JSON plugin reads and parses valid JSON, and then "flattens" the JSON into
key-value pairs. It only extracts JSON nodes with values. Nodes that represent
other JSON objects or JSON arrays are recursed and the name of the node followed
by a backslash is prefixed to any value therein's property name. The property
name used for each node is either its key name (if it was found in an object),
or its index (if it was found in an array).

It's easier to understand looking at an example:
```json
{
  "numberOfAccounts": 2,
  "accounts": [
    {
      "name": "alvin",
      "role": "admin"
    },
    {
      "name": "carl",
      "role": "user"
    }
  ]
}
```
The above JSON will produce the following properties:
- `numberOfAccounts=2`
- `accounts\0\name=alvin`
- `accounts\0\role=admin`
- `accounts\1\name=carl`
- `accounts\1\role=user`

#### Lists
There is a special exception applied to the algorithm above: if an array
contains a homogenous list of booleans, strings, floating-point numbers,
or integers, the entire array is converted into a comma-separated list and
used as the value for the current node. If the array is **not** one of the
above types, or it contains a mixture of types, the standard rules apply.

Empty lists are considered to be homogenous and their value will be an empty
string. This works because any rwConfig property with a list type will
interpret empty strings as an empty list.

For example:
```json
{
  "strings": ["a", "b", "c"],
  "ints": [1, 2, 3, 4, 5],
  "floats": [1.5, 2.5, 3.5],
  "mixed": [1, 2.5, 3]
}
```
The above JSON will produce the following properties:
- `strings=a,b,c`
- `ints=1,2,3,4,5`
- `floats=1.5,2.5,3.5`
- `mixed\0=1`
- `mixed\1=2.5`
- `mixed\2=3`

The `mixed` array falls back to the original rules because it contains a
mixture of integers and floating-point numbers. This is not supported in
rwConfig lists.

#### Null Values
Null values are represented as the string "null". Attempting to load JSON
which has a `null` value for a property declared in the `rwconfig` file as
any type but `string` will result in an exception at startup. This is by
design.

#### Avoiding Naming Conflicts
Consider the following JSON:
```json
{
  "a": {
    "b": "wazoo"
  },
  "a\\b": "what happens here?"
}
```
Remember that the escaped backslash in the second top-level property will be
interpreted as a literal backslash when this text representation of JSON is
ingested by the JSON engine. This creates a dilemma with our property naming
system. It's ambiguous which value the property name `a\b` should refer to.

In order to preclude the possibility of a naming conflict while flattening,
the following rules are applied in order:
1. If a property key contains a literal backslash, it is escaped with another
   backslash.
1. If a property key is empty (legal in the JSON spec), it is renamed to
   `empty\key`.

As a result, the JSON shown above will produce the following properties:
- `a\b=wazoo`
- `a\\b=what happens here?`

Any potential conflicts are avoided.

In practice these rules should affect relatively few JSON sources, since it is
not common to have empty property keys or property keys with backslashes. In any
case the data is still there; you just have to access it under a slightly tweaked
name.

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
The YAML plugin reads and parses valid YAML files, and then "flattens" the node
graph into key-value pairs. It only extracts YAML nodes with values. Nodes that
represent other YAML objects or sequences are recursed and the name of the node
followed by a backslash is prefixed to any value therein's property name. The
property name used for each node is either its key name (if it was found in
an object), or its index (if it was found in a sequence).

It's easier to understand looking at an example:
```yaml
numberOfAccounts: 2
accounts:
  - name: alvin
    role: admin
  - name: carl
    role: user
```
The above YAML will produce the following properties:
- `numberOfAccounts=2`
- `accounts\0\name=alvin`
- `accounts\0\role=admin`
- `accounts\1\name=carl`
- `accounts\1\role=user`

#### Lists
There is a special exception applied to the algorithm above: if a sequence
contains a homogenous list of booleans, strings, floating-point numbers,
or integers, the entire sequence is converted into a comma-separated list
and used as the value for the current node. If the array is **not** one of
the above types, or it contains a mixture of types, the standard rules
apply.

Empty sequences are considered to be homogenous and their value will be an
empty string. This works because any rwConfig property with a list type
will interpret empty strings as an empty list.

For example:
```yaml
strings: [a, b, c]
ints:
  - 1
  - 2
  - 3
  - 4
  - 5
floats: [1.5, 2.5, 3.5]
mixed: [1, 2.5, 3]
```
The above YAML will produce the following properties:
- `strings=a,b,c`
- `ints=1,2,3,4,5`
- `floats=1.5,2.5,3.5`
- `mixed\0=1`
- `mixed\1=2.5`
- `mixed\2=3`

The `mixed` array falls back to the original rules because it contains a
mixture of integers and floating-point numbers. This is not supported in
rwConfig lists.

#### Null Values
Null values are represented as the string "null". Attempting to load YAML
which has a `null` value for a property declared in the `rwconfig` file as
any type but `string` will result in an exception at startup. This is by
design.

#### Avoiding Naming Conflicts
Consider the following YAML:
```yaml
a:
  b: wazoo
a\b: what happens here?
```
This creates a dilemma with our property naming system. It's ambiguous which
value the property name `a\b` should refer to.

In order to preclude the possibility of a naming conflict while flattening,
the following rules are applied in order:
1. If a property key contains a literal backslash, it is escaped with another
   backslash.
1. If a property key is empty (legal in the YAML spec), it is renamed to
   `empty\key`.
1. If a property key is null (legal in the YAML spec), it is renamed to
   `null\key`.

As a result, the YAML shown above will produce the following properties:
- `a\b=wazoo`
- `a\\b=what happens here?`

Any potential conflicts are avoided.

In practice these rules should affect relatively few YAML sources, since it is
not common to have empty property keys, null keys, or keys with backslashes. In
any case the data is still there; you just have to access it under a slightly
tweaked name.

#### Merge Keys
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
The XML plugin reads and parses valid XML, and then "flattens" the XML into
key-value pairs. It only extracts properties from elements or attributes with
a value. Elements that contain attributes or other XML elements are recursed,
and the name of this element followed by a backslash is prefixed to any value
therein's property name.

If an element contains multiple elements with the same name, it is considered
to be an array. This is represented as the nested elements' name, followed by
a backslash, followed by the
element's index.

It's easier to understand looking at an example:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>com.foo</groupId>
    <artifactId>example</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
        </dependency>
    </dependencies>
</project>
```
The above XML will produce the following properties:
- `project\xmlns=http://maven.apache.org/POM/4.0.0`
- `project\groupId=com.foo`
- `project\artifactId=example`
- `project\dependencies\dependency\0\groupId=org.slf4j`
- `project\dependencies\dependency\0\artifactId=slf4j-api`
- `project\dependencies\dependency\1\groupId=org.slf4j`
- `project\dependencies\dependency\1\artifactId=slf4j-simple`

#### Lists
There is a special exception applied to the algorithm above: if an array
contains a homogenous list of booleans, strings, floating-point numbers,
or integers, the entire array is converted into a comma-separated list and
used as the value for the current node. If the array is **not** one of the
above types, or it contains a mixture of types, the standard rules apply.

For example:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<foo>
  <ints>
    <val>1</val>
    <val>3</val>
    <val>5</val>
  </ints>
  <floats>
    <val>1.5</val>
    <val>3.5</val>
    <val>5.5</val>
  </floats>
  <mixed>
    <val>1</val>
    <val>2.5</val>
    <val>3</val>
  </mixed>
</foo>
```
The above XML will produce the following properties:
- `foo\ints\val=1,3,5`
- `foo\floats\val=1.5,3.5,5.5`
- `foo\mixed\val\0=1`
- `foo\mixed\val\1=2.5`
- `foo\mixed\val\2=3`

The `mixed` array falls back to the original rules because it contains a
mixture of integers and floating-point numbers. This is not supported in
rwConfig lists.

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
This is a example plugin which prefixes the source name to all properties that it
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
