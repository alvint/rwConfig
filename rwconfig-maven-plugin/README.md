# Checking your code against your `rwconfig`

Two tools, one set of rules. The `rwconfig-analyzer` module does the checking;
the Maven plugin and the VS Code extension are front ends, so a finding reads
the same wherever you meet it.

## What is checked

| rule | severity | what it catches |
|---|---|---|
| `unknown-property` | error | `config.getInt("prot")` when nothing declares `prot` - the nearest declared name is suggested |
| `wrong-type` | error | `config.getString("port")` when `port` is an `int` - names the getter that would work |
| `instance-get-without-set` | error | `Config.Instance.get()` when nothing anywhere calls `set` |
| `command-line-source-without-args` | error | a `commandLineArguments` source declared, but only `ConfigFactory.create()` called |
| `source-without-type` | error | a source named in `rwc.sources` with no `type` setting |
| `source-missing-setting` | error | a built-in source type missing something it needs, such as a `properties` source with no `location` |
| `unread-property` | info | a property declared and never read |

**No config source is ever loaded.** Only the declarations in the `rwconfig`
file and the Java sources are read, so the checks work with no network, no
database, and no secrets - which is the normal state of a developer's machine.
That is the point of declaring properties in one file.

A property name that is not a literal - `config.getInt(name)` - cannot be
checked and is left alone rather than guessed at. A source of a plugin type is
checked only for having a type: what a plugin requires is known to the plugin,
which would have to be loaded to ask.

A name held in a constant - `config.getInt(Props.PORT)` - is resolved to the
string it stands for, in the file that holds it or any other, and is then checked
like a name written at the call site. Resolving it means attributing the sources
rather than only parsing them, which is several times the work, so it is done
only when a read actually names something that could be a constant.

What cannot be resolved is a name the code works out at run time -
`config.getString(name)` inside a loop over `getPropertyNames()`, say. Such a
read could be of any property, so no property can be shown to be unread, and the
unread check is skipped for the whole project rather than reporting properties
that are read after all. The other checks are unaffected.

## Maven

```xml
<plugin>
    <groupId>net.rabbitware.config</groupId>
    <artifactId>rwconfig-maven-plugin</artifactId>
    <version>(same version as rwConfig)</version>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>
```

It runs at `process-sources` - before compiling, since only the sources are
needed and a misread property is more useful before the module is built than
after. A project with no `rwconfig` file is skipped silently, so this is safe to
put in a parent pom.

| parameter | default | |
|---|---|---|
| `rwconfig.file` | `src/main/resources/rwconfig`, then `rwconfig` | where the file is |
| `rwconfig.failOnError` | `true` | whether errors fail the build |
| `rwconfig.reportUnread` | `true` | whether to mention unread properties |
| `rwconfig.skipRules` | none | rule ids to ignore, e.g. `unread-property` |
| `rwconfig.skip` | `false` | skip the check entirely |

## The command line

The analyzer runs on its own, which is how the editor extension uses it:

```
java -cp <jars> net.rabbitware.config.analyzer.Main \
     check --rwconfig src/main/resources/rwconfig --source src/main/java
```

It writes one JSON object per line. There is also `test-sources`, which loads
every source exactly as the application would - and therefore opens whatever the
file describes. Run it when you want to know whether the application would start
*here*; never on a timer.
