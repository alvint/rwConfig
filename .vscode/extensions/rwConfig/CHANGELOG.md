# Changelog

The version follows the rwConfig library: the extension ships the analyzer, so
the two are one thing to install and one thing to reason about. `package-jars.sh`
takes the version from the jars it bundles, so there is nothing to keep in step
by hand.

## 0.2.0

- Ships rwConfig 0.2.0: change detection for config sources, `.env` files, and
  optional HTTP credentials for any source that takes a location.
- Checks the library settings themselves - a misspelled `rwc.` setting, one for a
  source that is not declared, or one the source's type has no use for. rwConfig
  accepts all of these silently, so they are only visible here.
- Checks change detection against the code: a polling interval nothing enables, a
  listener on a `Config` built without change detection, a listener for a source
  that does not exist, and change detection with nothing that can ever change.
- Checks credentials: ones that would never be sent, ones that would go
  unencrypted, and a `password` with no `username`.
- Warns when a secret-looking property is given a default in the `rwconfig` file,
  which is a credential in a committed file.

## 0.1.1

- The marketplace listing now shows the editor catching a mistyped property
  name. No functional change.

## 0.1.0

First release. Syntax highlighting for `rwconfig` files, and checks that a
project's Java code matches what its `rwconfig` file declares:

- a property read that nothing declares, or read as the wrong type, with a
  "did you mean" suggestion for a near miss
- `Config.Instance.get()` where nothing calls `set`
- a `commandLineArguments` source where the code only calls `create()`
- a source named in `rwc.sources` with no `type`, or a built-in type missing a
  setting it needs
- properties nothing reads, when every read names a property that can be pinned
  down

`rwConfig: Test config sources` loads every source the way the application
would - on request only, never automatically.
