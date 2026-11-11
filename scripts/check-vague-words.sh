#!/usr/bin/env bash
# 평가기준 문서의 모호어 검사. 0건이면 통과.
# 사용법: ./scripts/check-vague-words.sh docs/평가기준.md
set -uo pipefail
FILE="${1:-docs/평가기준.md}"
[ -f "$FILE" ] || { echo "FAIL: $FILE 없음"; exit 1; }

BANNED='제대로|적절|올바르|알맞|충분|명확|대략|약간|정도로|여러|일부|가능하면|필요시|권장|좋은|나쁜|빠르|느리|자연스|직관적|편리|간단|복잡|매끄|안정적|균일|일관|품질|완성도|적당|합리적|효율|최적|우수|훌륭|개쌉|JOAT|대충|느낌|그럴듯|적당히'

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

# BANNED-LIST 구역 제거
sed '/<!-- BANNED-LIST-START -->/,/<!-- BANNED-LIST-END -->/d' "$FILE" > "$TMP"

if grep -nE "$BANNED" "$TMP" > "$TMP.hits" 2>/dev/null; then
  N=$(grep -c . "$TMP.hits" || true)
  echo "FAIL: ${N}건"
  cat "$TMP.hits"
  rm -f "$TMP.hits"
  exit 1
fi
rm -f "$TMP.hits"
echo "PASS: 모호어 0건 ($FILE)"
exit 0
