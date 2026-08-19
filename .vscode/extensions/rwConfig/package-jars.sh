#!/bin/sh
# Populate `lib/` for distribution. Only needed when building a .vsix; inside
# this repo the extension prefers the jars in each module's `target`.
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
mkdir -p "$here/lib"
mvn -q -f "$root/pom.xml" -pl rwconfig-analyzer -am install -DskipTests
# One jar per module, by name. A glob would happily copy two versions of the
# same module after a version change - `target` keeps whatever was built before
# - and the extension would then run with both on its class path.
copy_one() {
    module=$1
    set -- "$root/$module/target/$module"-*.jar
    if [ ! -f "$1" ]; then
        echo "no jar for $module - run a build first" >&2
        exit 1
    fi
    if [ "$#" -gt 1 ]; then
        echo "$# jars for $module in target - run \`mvn clean\` and build again:" >&2
        for jar in "$@"; do echo "    $(basename "$jar")" >&2; done
        exit 1
    fi
    cp "$1" "$here/lib/"
}
copy_one config
copy_one plugin-api
copy_one rwconfig-analyzer
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
