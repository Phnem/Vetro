#!/usr/bin/env bash
# UI guardrails (ast-grep, rules in rules/, config in sgconfig.yml).
#
#   errors   — fail the run: scroll containers without the iOS fling (IosScroll).
#   warnings — reported, don't fail: raw Color(0x…) and raw spring()/tween() outside the theme.
#
# Usage: scripts/lint-ui.sh [path…]   (default: app/src/main/java)
# Needs Node (npx); ast-grep is fetched on first run.
set -euo pipefail
cd "$(dirname "$0")/.."

paths=("$@")
[ ${#paths[@]} -eq 0 ] && paths=(app/src/main/java)

sg() { npx -y -p @ast-grep/cli ast-grep "$@"; }

echo "== warnings (not failing)"
{ sg scan --json=compact "${paths[@]}" 2>/dev/null || true; } | node -e '
  let s = ""; process.stdin.on("data", d => s += d).on("end", () => {
    const counts = {};
    for (const m of JSON.parse(s)) if (m.severity === "warning") counts[m.ruleId] = (counts[m.ruleId] || 0) + 1;
    for (const [id, n] of Object.entries(counts)) console.log(`  ${id}: ${n}`);
  });'

echo "== errors"
# Exit code is non-zero when any error-level rule matches.
sg scan --filter 'scroll-.*' "${paths[@]}"
echo "  none"
