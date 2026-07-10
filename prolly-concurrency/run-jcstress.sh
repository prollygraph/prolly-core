#!/usr/bin/env bash
# Run the jcstress memory-model harnesses in prolly-concurrency.
#
# jcstress does NOT run under surefire — it uses its own forked runner. The
# jcstress-core annotation processor generates the runner stubs (*_jcstress)
# into target/test-classes during test-compile, and jcstress-core ships
# org.openjdk.jcstress.Main, so no shaded uber-jar is needed: we run Main with
# the module's test classpath directly. Forked JVMs get the project's
# --enable-native-access flag (Foreign Function & Memory native access).
#
# Usage: ./run-jcstress.sh [test-name-regexp] [mode]
#   test-name-regexp : default "Jcstress"  (every harness in this module ends in Jcstress)
#   mode             : sanity | quick | default | tough | stress  (default: quick)
#
# Examples:
#   ./run-jcstress.sh                              # all harnesses, quick mode
#   ./run-jcstress.sh InMemoryNodeStore sanity     # one target, fastest preset
set -euo pipefail

MOD="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$MOD/.." && pwd)"
FILTER="${1:-Jcstress}"
MODE="${2:-quick}"

cd "$ROOT"

# 1. Compile harnesses + generate the jcstress runner stubs.
mvn -q -pl prolly-concurrency -am test-compile -Dprolly.concurrency.skip=false

# 2. Resolve the full test classpath (modules under test + jcstress-core + ...).
mvn -q -pl prolly-concurrency dependency:build-classpath \
  -Dmdep.outputFile=target/test-cp.txt -DincludeScope=test

CP="$MOD/target/test-classes:$(cat "$MOD/target/test-cp.txt")"

# 3. Run jcstress. Preview/native flags must reach the forked JVMs. Run from
#    target/ so jcstress's raw "jcstress-results-*.bin.gz" dump (written to the
#    working dir) lands in the gitignored build dir, not the repo.
cd "$MOD/target"
exec java -cp "$CP" org.openjdk.jcstress.Main \
  -t "$FILTER" -m "$MODE" \
  -jvmArgs "--enable-native-access=ALL-UNNAMED" \
  -r "$MOD/target/jcstress-results"
