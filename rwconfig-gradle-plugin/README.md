# rwConfig Gradle plugin

Checks a project's Java sources against its `rwconfig` file, so a misspelled
property name or the wrong getter fails the build rather than the application.

```kotlin
plugins {
    java
    id("net.rabbitware.rwconfig") version "0.3.0"
}
```

How to use it - the settings, what is checked, and how it differs from the
Maven plugin - is in [Checking your code against your
`rwconfig`](../rwconfig-maven-plugin/README.md#gradle). This page is about
building it.

## How it works

The plugin does no checking itself. `rwconfigCheck` runs the analyzer's command
line - `net.rabbitware.config.analyzer.Main check` - in a JVM of its own, from
the project's Java toolchain, and reads the one JSON object per line it prints.
That keeps the plugin small and loadable on the Java 17 that Gradle 9 runs on,
while the analyzer gets the Java 21 it needs, and keeps the analyzer's own
dependencies out of Gradle's class path.

The analyzer is not bundled. It is resolved in the user's build, from their
repositories, through the `rwconfig` configuration, and defaults to the plugin's
own version.

## Building

This is a Gradle build beside the Maven one, not a module of it. Its version is
read from the root `pom.xml`, so a version bump there moves this plugin too.

The functional tests run real builds against the analyzer and the library in
the local Maven repository, so install those first:

```
mvn install                   # in the parent directory
cd rwconfig-gradle-plugin
./gradlew build
```

`./gradlew check` runs the tests on their own:

- `test` - unit tests for reading the analyzer's output.
- `functionalTest` - TestKit builds of small projects: what is reported, every
  setting, where the file is found, where the task runs, up-to-date checks, the
  configuration cache, the Java toolchain, and the oldest supported Gradle
  (8.5) as well as the current one. Each build runs with `--warning-mode=fail`,
  so a deprecated use of Gradle fails the tests. They need a Java 21 installed,
  which Gradle finds on its own.

The tests use whatever is in `~/.m2`, so after changing the analyzer, run
`mvn install` again before trusting them.

## Publishing

The plugin goes to the [Gradle Plugin Portal](https://plugins.gradle.org), not
Maven Central. Both commands below need a Plugin Portal API key, as
`gradle.publish.key` and `gradle.publish.secret` in `~/.gradle/gradle.properties`
- which `./gradlew login` writes for you:

```
./gradlew publishPlugins --validate-only   # check everything without uploading
./gradlew publishPlugins
```

The analyzer version it defaults to must already be on Maven Central, or no
build that applies the plugin can resolve it - so publish the Maven artifacts
first. The first version of a new plugin id is reviewed by the Plugin Portal
before it appears.
