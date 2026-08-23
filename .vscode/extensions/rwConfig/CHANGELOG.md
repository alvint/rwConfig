# Changelog

The version follows the rwConfig library: the extension ships the analyzer, so
the two are one thing to install and one thing to reason about. `package-jars.sh`
takes the version from the jars it bundles, so there is nothing to keep in step
by hand.

## 0.2.0

- Ships rwConfig 0.2.0, which adds change detection for config sources.

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
