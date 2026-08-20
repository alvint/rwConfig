# rwConfig for VS Code

![Catching a typo before the app runs](https://raw.githubusercontent.com/alvint/rwConfig/main/docs/demo.gif)

rwConfig is a simple, lightweight library that provides a unified and _fast_
interface for reading configuration information from a wide range of config
sources.

What distinguishes rwConfig from the pack is its design philosophy: define what
you need and what it should look like ahead of time, and validate that you have
what you need at coding time (with this extension) and on startup. This means
that you can rest easier knowing that you won't be surprised by configuration
issues when it's too late to do something about it.

For more information see the [project page](https://github.com/alvint/rwConfig)
on GitHub.

This extention provides syntax highlighting for `rwconfig` files, and checks
that your Java code matches what they declare.

## Highlighting

Types, allowed values and ranges, escapes, list items, and library settings are
each highlighted, and anything the parser would reject is marked as invalid -
the grammar is built from the same rules as the parser, so what the editor calls
wrong is what the library would call wrong.

## Checking

When a workspace has an `rwconfig` file, Java sources are checked against it and
problems appear as diagnostics: reading a property nothing declares, reading one
as the wrong type, using `Config.Instance.get()` when nothing sets it, and a
`commandLineArguments` source with only a no-argument `create()`. The `rwconfig`
file is checked too - a source named in `rwc.sources` with no `type`, or a
built-in type missing a setting it needs, is underlined where the source is
named. Properties that nothing reads are reported as information - though only
when every read in the project names a property it can pin down, whether written
at the call site or held in a constant, since a name worked out at run time could
be reading any of them.

The checking is done by the `rwconfig-analyzer` jar, the same one the Maven
plugin uses, so the editor and the build never disagree.

**Config sources are not loaded.** Only the declarations are read, so the checks
need no network, database, or secrets. To find out whether the whole
configuration would load on this machine, run **rwConfig: Test config sources**
from the command palette - that one really does open every source the file
describes, which is why it only happens when you ask.

## Settings

| setting | default | |
|---|---|---|
| `rwconfig.file` | *(search)* | path to the file; empty searches `src/main/resources/rwconfig` then `rwconfig` |
| `rwconfig.sourceRoots` | `["src/main/java"]` | Java sources to check |
| `rwconfig.javaPath` | *(PATH)* | which `java` to run the analyzer with |
