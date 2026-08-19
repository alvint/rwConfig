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
echo "packaged $(ls "$here/lib" | wc -l | tr -d ' ') jars into $here/lib"
