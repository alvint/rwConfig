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
    # Only the code jar. A release build also leaves `-sources` and `-javadoc`
    # beside it, and neither belongs on the extension's class path.
    set -- $(ls "$root/$module/target/$module"-*.jar 2>/dev/null \
             | grep -v -e '-sources\.jar$' -e '-javadoc\.jar$')
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

# Take the extension's version from the library it is about to ship. The two are
# one product to a user - the extension is only useful with the analyzer inside
# it - and a version written into `package.json` by hand goes stale the first
# time the library moves. Derived from the jar that was just copied, so it names
# the code that is actually in the package rather than what the pom said.
version=$(basename "$(ls "$here/lib"/plugin-api-*.jar)" .jar | sed 's/^plugin-api-//')
case "$version" in
    # The marketplace takes x.y.z and nothing else - a SNAPSHOT would be rejected
    # at publish time, long after this script has finished. `case` matches globs,
    # not regular expressions, so the digits-and-dots test has to come first: a
    # trailing `*` in the shape test alone would happily swallow `-SNAPSHOT`.
    *[!0-9.]*)
        echo "library version \`$version\` is not usable as an extension version (needs x.y.z)" >&2
        exit 1
        ;;
    [0-9]*.[0-9]*.[0-9]*) ;;
    *)
        echo "library version \`$version\` is not usable as an extension version (needs x.y.z)" >&2
        exit 1
        ;;
esac
previous=$(node -p "require('$here/package.json').version")
if [ "$previous" != "$version" ]; then
    node -e "
        const fs = require('fs');
        const path = '$here/package.json';
        const pkg = JSON.parse(fs.readFileSync(path, 'utf8'));
        pkg.version = '$version';
        fs.writeFileSync(path, JSON.stringify(pkg, null, 2) + '\n');
    "
    echo "extension version $previous -> $version (following the library)"
fi

echo "packaged $count jars into $here/lib (rwConfig $version)"
