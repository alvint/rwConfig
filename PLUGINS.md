# Plugins
These plugins are currently available and are built with the project.
<br><br>
## YAML (plugin.yaml)
Loads properties from a YAML file.
### Required Properties
- `sourceType`
  - currently supports `file`, `classpath`, and `url`
- `path`
  - the path to the configuration source
### Optional Properties
- `resolveMergeKeys` (default is `true`)
  - The YAML version 1.2 specification removed merge key support, although many
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
property name used for each node or is either its key name (if it was found in
an object), or its index (if it was found in a sequence).

It's easier to understand looking at an example:
```yaml
numberOfAccounts: 2
accounts:
  - name: "alvin"
    role: "admin"
  - name: "carl"
    role: "user"
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
floats: [1.5, 2.5, 3.5],
mixed: [1, 2.5, 3]
```
The above YAML will produce the following properties:
- `strings=a, b, c`
- `ints=1, 2, 3, 4, 5`
- `floats=1.5, 2.5, 3.5`
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
This creates a dilemma with our naming system. It's ambiguous which value
the property name `a\b` should refer to.

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

## JSON (plugin.json)
Loads properties from a JSON file.
### Required Properties
- `sourceType`
  - currently supports `file`, `classpath`, and `url`
- `path`
  - the path to the configuration source
### Details
The JSON plugin reads and parses valid JSON, and then "flattens" the JSON into
key-value pairs. It only extracts JSON nodes with values. Nodes that represent
other JSON objects or JSON arrays are recursed and the name of the node followed
by a backslash is prefixed to any value therein's property name. The property
name used for each node or is either its key name (if it was found in an object),
or its index (if it was found in an array).

It's easier to understand looking at an example:
```json
{
  "numberOfAccounts": 2,
  "accounts" [
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
- `strings=a, b, c`
- `ints=1, 2, 3, 4, 5`
- `floats=1.5, 2.5, 3.5`
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
ingested by the JSON engine. This creates a dilemma with our naming system.
It's ambiguous which value the property name `a\b` should refer to.

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

## XML (plugin.xml)
Loads properties from an XML file.
### Required Properties
- `sourceType`
  - currently supports `file`, `classpath`, and `url`
- `path`
  - the path to the configuration source
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
The above JSON will produce the following properties:
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
The above JSON will produce the following properties:
- `foo\ints\val=1,3,5`
- `foo\floats\val=1.5, 3.5, 5.5`
- `foo\mixed\val\0=1`
- `foo\mixed\val\1=2.5`
- `foo\mixed\val\2=3`

The `mixed` array falls back to the original rules because it contains a
mixture of integers and floating-point numbers. This is not supported in
rwConfig lists.

## Prefix (plugin.prefix)
This is a example plugin which prefixes the source name to all properties that it
loads.
### Required Properties
- `mediaType`
  - currently supports `properties`
- `sourceType`
  - currently supports `file`, `classpath`, and `url`
- path
  - the path to the configuration source
### Details
The plugin currently derives the prefix from the source name, but this may change
to a required property.
