# Changelog

Notable changes to the rwConfig library. The VS Code extension has [its own
changelog](.vscode/extensions/rwConfig/CHANGELOG.md), and follows this version:
the extension ships the analyzer, so the two are one thing to reason about.

This project uses [semantic versioning](https://semver.org). Before 1.0 the file
format and the Java API may still change between minor versions; anything that
would break an existing `rwconfig` file is called out here.

## 0.3.0

Arbitrary-precision numbers, a security fix in the HOCON plugin, and a long run
of fixes to how values are read: exact decimals from YAML and HOCON, arrays that
mix whole numbers and decimals, Windows paths from any source, and escape
sequences. Several of those change behavior that existing files or code may
rely on, so they are gathered here first.

### Upgrading from 0.2.0

- A HOCON source that uses `include`, or substitutes an environment variable,
  needs `trusted = true` - see Security.
- `Config.PropertyType` is now `RuntimeType`, with four new constants.
- A value from a config source is no longer read with escape sequences, so a
  source value like `\e` is kept as those two characters. List properties are
  unchanged.
- `\.` and `\]` are errors in a value; they belong only inside an allowed
  values list.
- In a HOCON source, an `int` or `long` written as `8080.0` or `2.0e3` is
  rejected, as it is from every other source.
- An array that mixes kinds of value, such as `[9.99, 10]`, used to be split
  into one property per element, so it could be read by declaring `prices\0`
  and `prices\1`. It now arrives as one list value, `prices`. Those element
  declarations get no value: they fail at startup, or quietly take their
  default if they have one. Declare `doubleList prices` instead.
- For plugin authors: an optional setting that is not set is left out of the
  map given to `setPluginProperties`.

### Added

- **`bigInteger` and `bigDecimal` property types**, with `bigIntegerList` and
  `bigDecimalList`, for numbers a `long` or a `double` cannot hold. They are
  read with `getBigInteger` and `getBigDecimal` (`getbi`, `getbd`) and their
  list forms, and the Maven plugin and the editor extension check reads through
  them like any other. A `bigDecimal` keeps every digit and the scale it was
  written with; allowed values compare by value, so `bigDecimal[0.0..1.0]`
  accepts `1.00`.
- **`trusted` on a HOCON source** (default `false`). It turns on the HOCON
  features that reach outside the document - `include`, and substitutions from
  system properties and environment variables. See Security.
- **A trusted HOCON source can substitute Java system properties.** They only
  fill in substitutions: unlike Typesafe Config's `ConfigFactory.load()`, they
  are not merged into the source and do not override the document's own values.
- **`ListValues` in the plugin API**, for a plugin that reads a nested format.
  It decides whether an array can be one list value and joins it, escaping each
  item so it reads back exactly. The bundled plugins use it.

### Changed

- **`Config.PropertyType` is now `RuntimeType`**, a top-level enum in
  `net.rabbitware.config`, and is what `getType` returns. **This breaks code
  that names `Config.PropertyType`** - replace it with `RuntimeType`; the
  constants keep their names. It also has four new constants, `BIG_INTEGER`,
  `BIG_DECIMAL`, `BIG_INTEGER_LIST`, and `BIG_DECIMAL_LIST`, so a `switch` over
  `getType()` with no `default` has to handle them. A class implementing
  `Config` itself, such as a test double, has the new getters to implement.
- **A value from a config source is taken as it is**, rather than read with the
  `rwconfig` file's escape sequences. A Windows path such as `C:\dir\new` - from
  the environment, a system property, the command line, or any file-based
  source - used to fail at startup with `invalid escape sequence \d`, or needed
  its backslashes doubled on top of the source's own escaping. **Text that
  looked like an escape sequence in a source value is now kept as it is**, so a
  source value of `\e` is those two characters rather than an empty string. A
  list property is the exception: its value is still read with commas and
  escapes, so `\,` and `\\` still mean a comma and a backslash inside an item.
- **`\.` and `\]` are rejected in a value**, as the documentation has always
  said. They are escapes only inside an allowed values list, and were accepted
  everywhere; the editor extension already marked them as errors. A default
  written as `c\.\.d` should be written `c..d`.
- **An array of plain values becomes one list value, whatever mix it holds.**
  Only an array of one kind - all strings, all whole numbers, all decimals, or
  all booleans - used to be joined, and any other was split into indexed
  properties. This allows more flexibility in how list properties are declared.
  For example, `doubleList foo = 1, 2.5, 3, 4.0` now works as expected.
- **A number in a HOCON source arrives exactly as written**, rather than as
  Typesafe Config normalizes it: `1.0` stays `1.0` rather than becoming `1`, and
  `2.0e3` stays `2.0e3` rather than becoming `2000`. **This can break an `int`
  or `long` property written with a fraction or an exponent** - `port = 8080.0`
  used to be rewritten to `8080` before rwConfig parsed it, and is now rejected,
  the same as it is from every other source. A `string` property gets the text
  as written.
- **An optional plugin setting the `rwconfig` file does not mention is left out
  of the map handed to `setPluginProperties`**, rather than included as a key
  mapped to `null`. `get` returns `null` either way; what changes is that
  `containsKey` now means the setting was given, and `getOrDefault` now returns
  the default. A third-party plugin that relied on every optional name being
  present as a key should read it with `get` instead.

### Fixed

- **A YAML source that did not set `resolveMergeKeys` failed at startup** with
  `invalid boolean value : null`. Every YAML source that took the default was
  affected, as would be any plugin reading an optional setting the same way.
   Fixed by the change to optional settings above.
- **The YAML plugin never received `username` or `password`**, so HTTP basic
  authentication was silently ignored for YAML sources. It listed its own
  optional setting in place of the inherited ones rather than alongside them.
- **YAML and HOCON sources lost digits from decimals.** Both libraries read a
  decimal into a `double`, so `1.00000000000000000001` arrived as `1.0` from
  YAML and `1` from HOCON, and `10.50` lost its trailing zero. Both now keep
  every digit, and the scale.
- **An array item could change on its way into a list.** A comma inside an item
  split it in two, so `["a,b", "c"]` read as three items; a leading space was
  trimmed away; and `[""]` read as an empty list rather than one empty string.
- **A list item or allowed value ending in an escaped backslash swallowed the
  next one.** `stringList paths = a\\, b` was read as the single item `a\, b`,
  because only the one character before a comma was checked, and the backslash
  there was taken as escaping it rather than as the second half of `\\`. The
  same was true of the `..` in a range. A comma is now escaped only by an odd
  number of backslashes before it.
- **A line ending in an escaped backslash was joined with the next line.**
  `string winDir = C:\\` ran the next declaration into its value, and failed
  with an invalid escape sequence, so no value could end in a backslash. Only an
  odd run of backslashes now continues a line, and a comment ending in `\\` no
  longer swallows the line after it.
- **A `\u` escape for a backslash or a dollar sign failed at startup.**
  `\u005c` and `\u0024` threw, because the escape was expanded with
  `Matcher.replaceAll`, which reads both characters as its own syntax. Escapes
  are now read in a single pass, so a character one produces is never read as
  the start of another - `a\u005cq` is `a\q`.

### Security

- **HOCON directives that reach outside the document are refused by default.**
  `include` in every form - bare, `file()`, `url()`, and `classpath()` - and
  substitutions that fall back to environment variables or system properties now
  require the source to set [`trusted = true`](PLUGINS.md#hocon-hoconplugin).
  Through version 0.2.0 these directives were always carried out, so a HOCON
  file from anywhere the application could fetch it could read local files into
  the configuration or make the application issue requests to addresses the
  author could not reach.

  **This is a breaking change for a HOCON source that uses `include` or an
  environment substitution.** Such a document now fails at startup with an
  error naming the source and this setting, rather than quietly loading less
  than it used to. Set `trusted = true` on that source to restore the old
  behavior, or declare the included document as a config source of its own.
  A HOCON file that uses no directives is unaffected.

  The 0.2.0 notes described this exposure as one rwConfig could not prevent.
  That was wrong on the facts - it can, and now does.
- **A secret that failed to parse was shown in the error.** Withholding a
  value covered only the allowed-values check, so `12x34` for an `int apiSecret`
  appeared in full - and a second time in the message of the parser's own
  exception, kept as the cause. Every error about a value now withholds it, as
  does the warning about a misplaced escaped space, and a withheld value's error
  no longer carries the parser's exception.

## 0.2.0

Change detection, `.env` files, and a much sharper analyzer. Existing `rwconfig`
files and code keep working - everything new is opt-in.

### Added

- **Change detection.** Ask for it with `ConfigFactory.create(true, ...)` and
  rwConfig watches the sources that can be watched, then tells you when one has
  changed. It notifies rather than reloads: the `Config` you hold stays the
  snapshot it was, and you decide when to build a new one. Register listeners
  with `addChangeListener`, for all sources or one, and release a config you have
  finished with using the new `Config.discard()`.
  `rwc.changeDetectionPollingInterval` sets how often sources are checked.
- **`.env` files** as a built-in source type, following Docker Compose's dialect.
  A `.env` file stands in for the environment, so a property declared `dbHost` is
  satisfied by `DB_HOST`.
- **Optional HTTP credentials** - `username` and `password` on any source that
  takes a `location`, used for basic authentication against http(s) URLs.
- **`changeQuery`** on the JDBC plugin, which is what turns change detection on
  for a database source. There is no portable way to ask a database whether a
  table has changed, so the query is yours to write.
- **Withholding values from error messages.** `rwc.<source>.secret` covers
  everything a source supplies; `rwc.redactSecretsByName` - on by default -
  covers properties whose names read like secrets.
- **Eleven new checks** in the Maven plugin and the VS Code extension, for
  mistakes rwConfig itself accepts in silence: misspelled `rwc.` settings,
  settings for a source that is not declared or that its type has no use for, a
  polling interval nothing enables, a listener on a config built without change
  detection or for a source that does not exist, change detection with nothing
  watchable, credentials that would never be sent or would go unencrypted, and a
  secret-looking property given a default in the committed file.

### Changed

- **Every built-in config source is now a plugin internally.** No change to how
  they are declared - `properties`, `directory`, `environmentVariables` and the
  rest are written exactly as before.
- **The plugin interface gained a change detection lifecycle**:
  `isChangeDetectionSupported`, `startChangeDetection`, `stopChangeDetection` and
  `isChanged`, replacing the listener-based methods. rwConfig polls; a plugin no
  longer pushes. **This is a breaking change for third-party plugins**, which
  need recompiling against the new interface.
- **`LocationBasedConfigSourcePlugin`** is a new base class for any plugin that
  reads from a location. It handles the `location` property, credentials, and
  watching, leaving one method to write.
- **`getPluginVersion()` now has a default** that reports the version its plugin
  API was built at, so a bundled plugin can no longer drift from the library.
- **Change detection over HTTP uses a `HEAD` request** and compares
  `Last-Modified`, rather than fetching the whole document.

### Fixed

- The polling interval is honored. It was measuring from the wrong instant and
  polling roughly 1.8 times as often as asked.
- `stopChangeDetection` no longer throws for an HTTP-backed source, and change
  detection can be stopped and started again.
- A config discarded while its polling thread was mid-cycle no longer ends that
  thread with an uncaught exception.

### Security

- **Values are withheld from error messages** when the property looks like a
  secret or its source is declared secret. A failed validation used to print the
  offending value, which put it in logs and in the VS Code output pane.
- **Passwords are no longer written to logs.** The JDBC plugin logged its whole
  property map at `INFO`, including the database password; location-based plugins
  did the same at `DEBUG`.
- **HTTP change detection has timeouts**, and a server that stops responding
  raises an error rather than blocking the polling thread for every source.
- Documented two exposures rwConfig cannot prevent: [a HOCON file can read local
  files and request URLs while it parses](PLUGINS.md#hocon-hoconplugin), and
  [secrets on the command line are readable by every user on the
  machine](docs/config-sources.md#commandlinearguments).

## 0.1.0

First release, and the first on Maven Central.

The library reads configuration from many sources behind one interface, and
checks every value against declarations made up front in an `rwconfig` file, so
a missing or malformed value stops the process at startup. Ships with plugins for
JSON, YAML, XML, HOCON, JDBC, and prefixed sources, a Maven plugin, and a VS Code
extension that checks the same things while you type.
