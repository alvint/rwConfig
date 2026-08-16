# Config Sources

A **config source** is somewhere property values can come from: a file,
environment variables, a database. The `rwconfig` file declares which sources
exist, in what order they win, and how to reach each one.

Everything on this page uses the `rwc.` prefix, which marks a line as
configuration for the library rather than a property of your application.

## Declaring sources

```
rwc.sources = args, local, environment

rwc.args.type = commandLineArguments
rwc.local.type = properties
rwc.local.location = file:app.properties
rwc.environment.type = environmentVariables
```

`rwc.sources` is a comma-separated list of names you invent. **Order is
precedence, best first.** Each name then gets its own settings under
`rwc.<name>.`, and `type` is always required.

Source names are yours to choose - `local`, `args`, `secrets`, `db`. They are
only labels tying `rwc.sources` to the settings below it.

`rwc.sources` itself is optional. Leave it out and there are no config sources
at all: every property takes the default declared for it in the `rwconfig`
file, and a property with no default fails at startup because nothing can
supply it. A file of nothing but declarations is a complete configuration.

## Precedence

The first source that has a value for a property wins. Anything the sources do
not set falls back to the default in the `rwconfig` file. So with the list
above:

```
args  >  local  >  environment  >  the default in the rwconfig file
```

A source having a property at all is what counts, not whether the value is
interesting - an empty value from `args` still beats `local`.

## Built-in source types

### `commandLineArguments`

Arguments of the form `name=value`, from the array you pass to
`ConfigFactory.create(args)`.

```
rwc.args.type = commandLineArguments
```

If you declare this source you **must** use `create(args)`. Calling `create()`
with no arguments while this source is declared is an error.

### `systemProperties`

JVM system properties, the `-D` ones.

```
rwc.system.type = systemProperties
```

The JVM sets dozens of system properties of its own, but they are not a
problem: this source is read by looking up each declared property by name, so
anything else in it is simply never seen.

### `environmentVariables`

Environment variables, looked up two ways in order:

1. the property name exactly as written
2. a normalized form: dots and camel-case boundaries become underscores, then
   everything is upper-cased

So `dbPassword` is found as `dbPassword` or `DB_PASSWORD`, and
`my.dotted.name` as `MY_DOTTED_NAME`.

```
rwc.environment.type = environmentVariables
```

### `properties`

A Java `.properties` file, from any [location](#locations).

```
rwc.local.type = properties
rwc.local.location = file:app.properties
```

### `directory`

A directory where each file is one property: the file name is the property
name, the file contents are the value.

```
rwc.secrets.type = directory
rwc.secrets.path = /run/secrets
```

This is the shape container runtimes and Kubernetes use for mounted secrets. A
file named `greeting` containing `Hello` gives you `greeting=Hello`. Whitespace
in the file - including newlines - is kept exactly. Only regular files (and
symlinks to them) are read; a file that cannot be read is an error.

Note this takes `path`, a plain filesystem path, not a `location`.

### Plugin types

Anything with a dot in the type name is a plugin:

```
rwc.settings.type = yaml.plugin
rwc.settings.location = file:settings.yaml
```

The bundled plugins are `yaml.plugin`, `json.plugin`, `xml.plugin`,
`jdbc.plugin`, and `prefix.plugin` - see [PLUGINS.md](../PLUGINS.md) for what
each one needs. To write your own, see [Writing a
plugin](writing-a-plugin.md).

## Unknown properties

By default, a property in a source that no `rwconfig` line declares is an
**error**. This catches typos: if a properties file says `prot=9000` and the
property is `port`, you hear about it at startup instead of wondering why the
value did not take.

When a source legitimately carries things your application does not care about,
turn the check off for that source:

```
rwc.settings.ignoreUnknownProperties = true
```

Accepts `true`/`yes`/`on`/`1` in any case; anything else, including omitting
it, means false. It is per-source, so an application-owned file can stay strict
while a shared one is lenient.

### Which sources this applies to

Sources fall into two groups, and the difference decides whether the check does
anything at all:

| source | read by | unknown properties |
|---|---|---|
| `properties`, `directory`, all plugins | reading everything it has | **detected** - set the flag if that is not what you want |
| `commandLineArguments` | reading every argument that looks like `name=value` | **detected** - set the flag if that is not what you want |
| `systemProperties`, `environmentVariables` | looking up each declared property by name | never seen, so the flag has no effect |

The last group cannot report an unknown property even in principle - it only
ever asks for names your `rwconfig` file declares. That is deliberate: an
application using system properties should not drown in errors about the JVM's
own, and the environment is full of variables that have nothing to do with your
program.

Command line arguments are different, and are checked. Every one of them was
typed deliberately, for this program, so `prot=9000` when you declared `port` is
far more likely to be a typo than a coincidence - exactly the mistake this
library exists to catch:

```
property `prot` is not defined in the `rwconfig` file, and config source `args`
does not allow unknown properties
```

An argument is only treated as a property assignment when its name is a legal
[property name](config-file.md#names), which begins with a letter. Your
application's own flags and positional arguments - `--verbose`, `-n=3`,
`--filter=foo`, `input.txt` - are left alone, as are the library's own
arguments. If your application does take bare `name=value` arguments of its own,
set `ignoreUnknownProperties` on the source.

## Locations

Anywhere a setting is called `location`, it takes a prefix:

| prefix | example |
|---|---|
| `file:` | `file:app.properties`, `file:/etc/myapp/app.properties` |
| `classpath:` | `classpath:app.properties` |
| `jar:` | `jar:file:/opt/app/lib/config.jar!/app.properties` |
| `http:` | `http://config.internal/app.properties` |
| `https:` | `https://config.internal/app.properties` |

`file:` paths may be relative, resolved against the working directory.

`http:` and `https:` use a 10 second connect timeout and a 10 second read
timeout, so a config server that stops responding fails the startup rather than
hanging it forever.

## Keeping secrets out of shared files

A source setting's value can be `<<` - a **deferred value**, meaning "the real
value is in a config source that was loaded before this one":

```
rwc.sources = secrets, db

rwc.secrets.type = directory
rwc.secrets.path = /run/secrets

rwc.db.type = jdbc.plugin
rwc.db.connectionString = jdbc:postgresql://db/app
rwc.db.query = SELECT key, value FROM config
rwc.db.username = app
rwc.db.password = <<
```

The database password is not in the file. The library looks for a property
named `rwc.db.password` in the sources already loaded - here, a file named
`rwc.db.password` in `/run/secrets`.

Two things worth knowing:

- The source holding the real value must come **earlier** in `rwc.sources`, so
  it is loaded first.
- The value is used to configure the source and is not exposed to your
  application. It never appears in `getPropertyNames()`, and the source it came
  from does not need `ignoreUnknownProperties` on its account.

If nothing supplies it, startup fails saying so - a deferred value that
resolves to nothing is never silently empty. The error names the source, so you
know how far back the search went:

```
library setting `rwc.db.password` is set to `<<`, but no config source loaded
before `db` contains this property
```

## Changing the `rwc.` prefix

If `rwc.` clashes with property names you need, change it:

```
rwc.root = myapp.

myapp.sources = environment
myapp.environment.type = environmentVariables
myapp.environment.ignoreUnknownProperties = true
```

The `rwc.root` line itself is always spelled `rwc.root`, whatever you set the
prefix to, and it must match exactly - `RWC.ROOT` is read as an ordinary
property name.

## A worked example

```
# args beat everything, then the local file, then the deployed defaults,
# then whatever the environment happens to have.
rwc.sources = args, secrets, local, deployed, environment

rwc.args.type = commandLineArguments

# secrets mounted by the runtime, one file per value
rwc.secrets.type = directory
rwc.secrets.path = /run/secrets

# a file an operator can drop next to the application
rwc.local.type = properties
rwc.local.location = file:app.properties

# defaults baked into the jar
rwc.deployed.type = properties
rwc.deployed.location = classpath:app.properties

rwc.environment.type = environmentVariables

int[1024:65535] port = 8080
string[dev, staging, prod] stage = dev
string apiKey
```

An operator can override the port on the command line, keep the API key in a
mounted secret, and ship defaults in the jar - without any of it being visible
in the application code.
