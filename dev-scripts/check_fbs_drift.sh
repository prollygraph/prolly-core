#!/bin/bash
# check_fbs_drift.sh — verify the vendored flatbuffers-generated sources match the schema.
#
# The generated serial/ classes (dolthub-java-port/src/main/java/serial/) are CHECKED IN and are the
# compile input (reproducible builds, no hard flatc dependency — plan D-1). This check is what keeps
# them honest: with a VERSION-PINNED flatc present, regenerate src/main/fbs/prolly.fbs into a temp
# dir and byte-diff against the vendored sources. Any difference means the schema and the generated
# code have drifted apart (someone edited prolly.fbs without regenerating, or hand-edited serial/).
#
# Usage:
#   dev-scripts/check_fbs_drift.sh            # warner: report drift, always exit 0 (unless misuse)
#   dev-scripts/check_fbs_drift.sh --error    # gate:   exit 1 on drift (CI ratchet target)
#
# Exit contract (plan wire-flatc-into-build D-2/D-3):
#   - flatc absent, or its version != the pinned ${flatbuffers.version}  -> SKIP warning, exit 0
#     (both modes: a missing/wrong toolchain must never fail the build — cross-version codegen
#     differences are not drift).
#   - drift found  -> report per-file; exit 0 in warner mode, exit 1 under --error.
#   - no drift     -> exit 0.
#
# Env overrides (testing): FLATC=<path> forces the flatc binary to use.
#
# The version pin is READ from prolly-dependencies/pom.xml's <flatbuffers.version> — the single
# declared place the runtime pins — so a flatbuffers upgrade moves runtime + compiler + regen in one
# coordinated commit. The deliberate-regen ritual is dev-scripts/regenerate-fbs.sh (writes in place
# + runs the tests); this script NEVER rewrites sources.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

MODE="warn"
if [ "${1:-}" = "--error" ]; then
  MODE="error"
elif [ -n "${1:-}" ]; then
  echo "usage: $0 [--error]" >&2
  exit 2
fi

FBS="dolthub-java-port/src/main/fbs/prolly.fbs"
VENDORED="dolthub-java-port/src/main/java/serial"
PIN_POM="prolly-dependencies/pom.xml"

skip() { printf 'fbs-drift: SKIP — %s\n' "$*"; exit 0; }

[ -f "${FBS}" ] || skip "schema ${FBS} not present"
[ -d "${VENDORED}" ] || skip "vendored dir ${VENDORED} not present"

# The single declared pin the runtime uses (plan D-2).
PINNED="$(sed -n 's/.*<flatbuffers.version>\(.*\)<\/flatbuffers.version>.*/\1/p' "${PIN_POM}" | head -1)"
[ -n "${PINNED}" ] || skip "cannot read <flatbuffers.version> from ${PIN_POM}"

# Locate flatc: env override -> installed path -> PATH.
if [ -n "${FLATC:-}" ]; then
  FLATC_BIN="${FLATC}"
elif [ -x /opt/flatc/bin/flatc ]; then
  FLATC_BIN=/opt/flatc/bin/flatc
elif command -v flatc >/dev/null 2>&1; then
  FLATC_BIN="$(command -v flatc)"
else
  skip "flatc not installed (run dev-scripts/install-flatc.sh); vendored sources not verified"
fi
[ -x "${FLATC_BIN}" ] || skip "flatc at ${FLATC_BIN} is not executable; vendored sources not verified"

ACTUAL="$("${FLATC_BIN}" --version 2>/dev/null | sed -n 's/.*flatc version \([0-9][0-9.]*\).*/\1/p')"
if [ "${ACTUAL}" != "${PINNED}" ]; then
  skip "flatc ${ACTUAL:-unknown} != pinned ${PINNED} (cross-version codegen diffs are not drift)"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

"${FLATC_BIN}" --java -o "${TMP}" "${FBS}"

if diff -r "${TMP}/serial" "${VENDORED}" > "${TMP}/drift.txt" 2>&1; then
  echo "fbs-drift: OK — ${VENDORED} is byte-identical to a fresh flatc ${PINNED} regen of ${FBS}"
  exit 0
fi

echo "fbs-drift: DRIFT between ${FBS} and the vendored ${VENDORED}:" >&2
cat "${TMP}/drift.txt" >&2
echo "fbs-drift: fix = run dev-scripts/regenerate-fbs.sh (regen + tests), review, commit" >&2
if [ "${MODE}" = "error" ]; then
  exit 1
fi
echo "fbs-drift: warner mode — not failing the build (gate with --error)" >&2
exit 0
