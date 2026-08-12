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
key-value pairs.

In order to preclude the possibility of a naming conflict while
flattening, the following rules are applied in order:
1. if a property key contains a literal backslash, it is escaped with another
   backslash
1. if a property key is empty (legal in the JSON spec), it is renamed to
   `empty\key`

In practice, these rules should affect relatively few JSON sources.

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
