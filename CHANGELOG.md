# Changelog

Notable changes to the rwConfig library. The VS Code extension has [its own
changelog](.vscode/extensions/rwConfig/CHANGELOG.md), and follows this version:
the extension ships the analyzer, so the two are one thing to reason about.

This project uses [semantic versioning](https://semver.org). Before 1.0 the file
format and the Java API may still change between minor versions; anything that
would break an existing `rwconfig` file is called out here.

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
