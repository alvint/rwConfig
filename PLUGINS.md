# Plugins
These plugins are currently available and are built with the project.
<br><br>
## JSON (plugin.json)
Loads properties from a JSON file.
### Required Properties
- `sourceType`
  - currently supports `file`, `classpath`, and `url`
- path
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
  "strings": [ "a", "b", "c"]
  "ints": [ 1, 2, 3, 4, 5],
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
1. if a property key contains a literal backslash, it is escaped with another
   backslash
1. if a property key is empty (legal in the JSON spec), it is renamed to
   `empty\key`
As a result, the JSON shown above will produce the following properties:
- `a\b=wazoo`
- `a\\b=what happens here?`

Any potential conflicts are avoided.

In practice these rules should affect relatively few JSON sources, since it is
not common to have empty property keys or property keys with backslashes. In any
case the data is still there; you just have to access it under a slightly tweaked
name.

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
