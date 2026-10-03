#!/usr/bin/env bash
# Unit-test coverage for the iOS SDK's pure target, QaidQuestsCore, with a gate.
#
#   scripts/coverage-ios.sh
#
# Runs `swift test --enable-code-coverage`, prints llvm-cov's per-file table for
# ios/Sources/QaidQuestsCore only, and writes:
#   coverage/ios/summary.json   llvm-cov's own JSON summary (per file and total)
#   coverage/ios/totals.json    {"lines", "functions", "regions"} percentages, for coverage.sh
# Exits non-zero when the tests fail or lines, functions or regions fall under the gate.
#
# Swift has no branch counter; regions (every `if`, `??`, `&&` arm and closure) are the
# branch measure here. QaidQuests (SwiftUI) compiles to nothing on macOS, so it has no
# coverage to report: its logic lives in Core, and the views stay thin.
set -euo pipefail

# The gate: one point under the measured numbers, never under 95. Raise it with the numbers.
# The env overrides exist to check the gate fails, e.g. GATE_LINES=100.
GATE_LINES="${GATE_LINES:-98}"
GATE_FUNCTIONS="${GATE_FUNCTIONS:-97}"
GATE_REGIONS="${GATE_REGIONS:-98}"

export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/coverage/ios"
SOURCES="$ROOT/ios/Sources/QaidQuestsCore"
# A shell alias or a toolchain manager can shadow `swift`; the system one is what CI has.
SWIFT=/usr/bin/swift
[ -x "$SWIFT" ] || SWIFT="$(command -v swift)"

mkdir -p "$OUT"
cd "$ROOT"

echo "== iOS: swift test --enable-code-coverage"
if ! "$SWIFT" test --enable-code-coverage >"$OUT/test.log" 2>&1; then
    cat "$OUT/test.log"
    echo "iOS tests failed" >&2
    exit 1
fi
grep -E "^[[:space:]]*Executed [0-9]+ tests?" "$OUT/test.log" | tail -n 1

BIN_DIR="$("$SWIFT" build --show-bin-path)"
PROFDATA="$BIN_DIR/codecov/default.profdata"
TEST_BIN="$BIN_DIR/QaidQuestsCoreTests.xctest/Contents/MacOS/QaidQuestsCoreTests"
for f in "$PROFDATA" "$TEST_BIN"; do
    [ -e "$f" ] || { echo "missing $f" >&2; exit 1; }
done

echo
echo "== iOS: QaidQuestsCore"
xcrun llvm-cov report "$TEST_BIN" -instr-profile="$PROFDATA" -show-branch-summary=false "$SOURCES"
xcrun llvm-cov export -summary-only "$TEST_BIN" -instr-profile="$PROFDATA" "$SOURCES" >"$OUT/summary.json"

/usr/bin/python3 - "$OUT" "$GATE_LINES" "$GATE_FUNCTIONS" "$GATE_REGIONS" <<'PY'
import json, os, sys

out, gates = sys.argv[1], dict(zip(("lines", "functions", "regions"), map(float, sys.argv[2:5])))
totals = json.load(open(os.path.join(out, "summary.json")))["data"][0]["totals"]
measured = {k: round(totals[k]["percent"], 2) for k in gates}
json.dump(measured, open(os.path.join(out, "totals.json"), "w"), indent=2)
print()
failed = False
for k, gate in gates.items():
    ok = measured[k] >= gate
    failed |= not ok
    print(f"{k:<10} {measured[k]:6.2f}%   gate {gate:g}%   {'ok' if ok else 'UNDER THE GATE'}")
sys.exit(1 if failed else 0)
PY
