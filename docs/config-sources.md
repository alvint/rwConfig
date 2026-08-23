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

Container runtimes and Kubernetes use this strategy for mounted secrets - see
[Kubernetes and container secrets](#kubernetes-and-container-secrets). A file
named `greeting` containing `Hello` gives you `greeting=Hello`. Whitespace in
the file (including newlines) is kept exactly. Only regular files (and symlinks
to them) are read; a file that cannot be read is an error.

Note this takes `path`, a plain filesystem path, not a `location`.

### `dotenv`

A `.env` file - the `KEY=value` format Docker Compose, container runtimes, and
local development tooling all use.

```
rwc.local.type = dotenv
rwc.local.location = file:.env
```

A `.env` file stands in for the environment, so names resolve the way
[`environmentVariables`](#environmentvariables) resolves them: a property
declared as `dbHost` is satisfied by `DB_HOST`, and by `dbHost` if that is what
the file happens to call it. Keys the file contains that no declaration claims
are passed through under their own names, so a typo in the file is still
reported as an unknown property rather than quietly ignored.

The format has no specification and implementations disagree at the edges. These
rules follow Docker Compose, which is the dialect most likely to have produced
the file:

```
# a comment
export DB_HOST=prod.example.com     # `export` is allowed, and ignored
DB_PORT = 5432                      # space around the `=` and value is ignored
PASSWORD=pa#ssword                  # a `#` not preceded by a space is part of the value
NOTE=value # this is a comment      # one that is preceded by a space is not
LITERAL='no \n escapes here'        # single quotes are literal
ESCAPED="a\tb"                      # double quotes process \n \r \t \\ and quotes
PADDED="  kept  "                   # quotes preserve spaces the value should have
CERT="line one
line two"                           # a quoted value may span lines
```

Two things it will not do:

- **Variables are not expanded.** `KEY=${OTHER}` is the literal text
  `${OTHER}`. Docker Compose expands them; rwConfig layers sources and has
  [deferred values](#keeping-secrets-out-of-shared-files) for this, and a value
  that quietly means something else depending on the environment is what this
  library exists to prevent.
- **It does not guess at a broken file.** A line that is not blank, a comment,
  or an assignment is an error naming the line, as is a quote that is never
  closed - rather than a value silently truncated at the end of the line.

### Plugin types

Anything with a dot in the type name is a plugin:

```
rwc.settings.type = yaml.plugin
rwc.settings.location = file:settings.yaml
```

The bundled plugins are `yaml.plugin`, `json.plugin`, `xml.plugin`, `hocon.plugin`,
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
ever asks for names your `rwconfig` file declares. The setting is
therefore always on for them, and trying to turn it *off* logs a warning at
startup: it asks for something the library cannot do. That is deliberate: an
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

### Loading config over HTTP

Any file-based source can read from a URL instead of a disk, so a config server
serving JSON is a config source with no extra machinery:

```
rwc.sources = remote
rwc.remote.type = json.plugin
rwc.remote.location = https://config.internal/app.json
```

Three things to know before relying on it:

- **It is fetched once, at startup.** The values are whatever the endpoint
  returned when the process began. With [change
  detection](java-api.md#noticing-that-a-source-has-changed) turned on rwConfig
  will poll the endpoint and tell you it has changed, but it still does not
  re-read it into the `Config` object you are holding. You must build a new
  Config to get the new values.
- **A failure is a startup failure.** If the endpoint is down, returns an error,
  or is slow, the process does not start. `http:` and `https:` use a 10 second
  connect timeout and a 10 second read timeout, so a config server that stops
  responding fails the startup rather than hanging it forever. This is usually
  what you want, but it does make the config server something your application
  cannot start without - give it a local file earlier in `rwc.sources` if you
  would rather degrade than fail.
- **Change detection asks the server, and needs it to answer.** A watched URL is
  checked with a `HEAD` request and its `Last-Modified` header compared with the
  last one seen. A server that does not send that header cannot be watched - the
  source is reported as unchanged forever - so a warning is logged at startup
  when the first check finds no header. The same 10 second timeouts apply, so an
  endpoint that stops responding raises an error event rather than blocking the
  polling thread.
- **Credentials are optional.** An endpoint behind HTTP basic authentication
  takes a `username` and a `password`:

  ```
  rwc.remote.type = json.plugin
  rwc.remote.location = https://config.internal/app.json
  rwc.remote.username = config-reader
  rwc.remote.password = <<
  ```

  Both are optional and available on every source that takes a `location`. The
  password should be a [deferred value](#keeping-secrets-out-of-shared-files),
  as above, rather than written in a file that gets committed. Setting a
  `password` without a `username` is an error, since it would never be sent.

  Only basic authentication is supported, and only over `https:`, in practice:
  the credentials are base64, which is encoding and not encryption, so rwConfig
  logs a warning if you send them over plain `http:`. Anything else - a bearer
  token, mTLS, a signed request - needs a [plugin](writing-a-plugin.md) that can
  supply it.

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

## Kubernetes and container secrets

Kubernetes mounts a Secret or a ConfigMap as a directory with one file per key,
which is exactly what the [`directory`](#directory) source reads. No adapter is
involved - the runtime's own format is already a config source.

Given a Secret:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
stringData:
  dbPassword: s3cret-value
  apiKey: AKIAEXAMPLE
```

mounted into the pod:

```yaml
volumes:
  - name: secrets
    secret:
      secretName: app-secrets
containers:
  - name: app
    volumeMounts:
      - name: secrets
        mountPath: /run/secrets
        readOnly: true
```

the `rwconfig` file needs three lines and the declarations:

```
rwc.sources = secrets
rwc.secrets.type = directory
rwc.secrets.path = /run/secrets

string dbPassword
string apiKey
```

A ConfigMap works the same way; mount it at a different path and declare it as
a second source.

### Why the mount layout does not matter

Kubernetes does not write those files directly. It writes a hidden timestamped
directory and fills the mount with symlinks pointing into it, so the mount
really looks like this:

```
..2026_08_19_12_00_00.data/    <- the real files
..data -> ..2026_08_19_12_00_00.data
dbPassword -> ..data/dbPassword
apiKey -> ..data/apiKey
```

It is laid out this way so that an update swaps one symlink atomically rather
than leaving a half-written file on disk. The `directory` source reads through
it correctly: symlinks to regular files are followed, and `..data` and the
timestamped directory are skipped because they are directories, not files.
Neither shows up as a property.

The values are a snapshot: rwConfig reads the directory once at startup, so a
Secret that Kubernetes updates in place reaches the application either when the
pod restarts, or when the app builds a new `Config` object. With [change
detection](java-api.md#noticing-that-a-source-has-changed) turned on you are
told the directory changed and can rebuild without waiting for a restart.

## Which sources can be watched

With [change detection](java-api.md#noticing-that-a-source-has-changed) turned
on, rwConfig checks the sources that can be checked and tells you when one has
changed. Whether a source takes part depends on where it reads from, not on
which type it is:

| | watched |
| --- | --- |
| third-party plugins | up to the plugin provider |
| the built-in `directory` source | yes |
| database sources via the provided `database` plugin | yes |
| file-based sources (`.properties` files, HOCON, YAML, etc.) on a `file:` location | yes |
| the same on a `jar:file:` location | yes - the jar is watched |
| the same on an `http:` or `https:` location | yes - a `HEAD` request, comparing `Last-Modified` |
| the same on a `classpath:` location | no |
| `dotenv` on a watchable location | yes - it is a file like any other |
| `jdbc.plugin` | only with a `changeQuery` - see [PLUGINS.md](../PLUGINS.md#jdbc-jdbcplugin) |
| `environmentVariables`, `systemProperties`, `commandLineArguments` | no |

The last two rows are not gaps. A classpath resource is inside the running
program and cannot change while it runs, and the environment, system properties,
and command line are fixed when the process starts. A source that cannot be
watched is never reported as changed, and nothing fails on its account.

## Changing the `rwc.` prefix

If `rwc.` clashes with property names you need, change it:

```
rwc.prefix = myapp.

myapp.sources = environment
myapp.environment.type = environmentVariables
```

It has to be the **first line that is not a comment or blank**. The prefix
applies to every line in the file, so a reader should not have to reach the
bottom to learn what the lines above meant - and an editor highlighting the
file cannot know at all until it gets there.

The `rwc.prefix` line itself is always spelled `rwc.prefix`, whatever you set the
prefix to, and it must match exactly - `RWC.PREFIX` is read as an ordinary
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

int[1024..65535] port = 8080
string[dev, staging, prod] stage = dev
string apiKey
```

An operator can override the port on the command line, keep the API key in a
mounted secret, and ship defaults in the jar - without any of it being visible
in the application code.
