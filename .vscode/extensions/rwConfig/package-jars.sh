#!/bin/sh
# Populate `lib/` for distribution. Only needed when building a .vsix; inside
# this repo the extension prefers the jars in each module's `target`.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
mkdir -p "$here/lib"
mvn -q -f "$root/pom.xml" -pl rwconfig-analyzer -am install -DskipTests
cp "$root"/config/target/config-*.jar "$here/lib/"
cp "$root"/plugin-api/target/plugin-api-*.jar "$here/lib/"
cp "$root"/rwconfig-analyzer/target/rwconfig-analyzer-*.jar "$here/lib/"
cp "$(find "$HOME/.m2/repository/org/slf4j/slf4j-api" -name 'slf4j-api-*.jar' ! -name '*sources*' | sort | tail -1)" "$here/lib/"
count=$(ls "$here/lib"/*.jar 2>/dev/null | wc -l | tr -d ' ')
# `vscode:prepublish` runs this before packaging. Publishing without the jars
# produces an extension that silently does nothing but highlight syntax, so a
# missing one has to stop the build rather than warn.
if [ "$count" -lt 4 ]; then
    echo "only $count jars in $here/lib - refusing to package an extension without the analyzer" >&2
    exit 1
fi
echo "packaged $count jars into $here/lib"
