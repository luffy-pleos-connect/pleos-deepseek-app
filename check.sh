#!/usr/bin/env bash
# MOCA 품질 게이트 자동 검사. 판정은 실제로 돈 명령의 출력으로만 한다.
# 사용법: ./check.sh [--quick]
#   --quick  : 서버·core 테스트만 (에뮬레이터·Gradle 빌드 생략)
set -uo pipefail
cd "$(dirname "$0")"

ROOT="$PWD"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAVA17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

PASS=0; FAIL=0; SKIP=0
declare -a ROWS=()

row() { # row ID 기준 결과 상세
  local id="$1" result="$2" detail="${3:-}"
  ROWS+=("$id|$result|$detail")
  case "$result" in
    PASS) PASS=$((PASS+1)) ;;
    FAIL) FAIL=$((FAIL+1)) ;;
    *)    SKIP=$((SKIP+1)) ;;
  esac
}

say() { printf '\n\033[1m── %s\033[0m\n' "$*"; }

# ─────────────────────────────── 1. 서버 (AT-07)
say "서버 테스트 (node --test)"
if [ -f server/core.test.mjs ]; then
  out="$(cd server && node --test 2>&1)"
  fails="$(printf '%s' "$out" | sed -n 's/^[^ ]* *fail \([0-9]*\)$/\1/p' | tail -1)"
  passes="$(printf '%s' "$out" | sed -n 's/^[^ ]* *pass \([0-9]*\)$/\1/p' | tail -1)"
  if [ "${fails:-1}" = "0" ] && [ -n "${passes:-}" ]; then
    row "AT-07" PASS "node --test 통과 ${passes}개"
  else
    row "AT-07" FAIL "fail=${fails:-?} pass=${passes:-?}"
  fi
else
  row "AT-07" SKIP "server/core.test.mjs 없음"
fi

# ─────────────────────────────── 2. :core 점수 엔진 (AT-01~06, AT-10)
say ":core 테스트 (JDK 17)"
if [ -x core/run-tests.sh ]; then
  out="$(cd core && JAVA_HOME="$JAVA17" ./run-tests.sh 2>&1)"
  if printf '%s' "$out" | grep -qiE '(^|[^a-z])(all tests passed|BUILD SUCCESS|failures: 0|tests passed)'; then
    row "AT-01~06,10" PASS "$(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
  else
    row "AT-01~06,10" FAIL "$(printf '%s' "$out" | tail -3 | tr '\n' ' ')"
  fi
else
  row "AT-01~06,10" SKIP "core/run-tests.sh 없음"
fi

if [ "$QUICK" = "1" ]; then
  say "요약 (--quick)"
  printf '%s\n' "${ROWS[@]}" | awk -F'|' '{printf "%-14s %-5s %s\n", $1, $2, $3}'
  printf '\nPASS %d / FAIL %d / SKIP %d\n' "$PASS" "$FAIL" "$SKIP"
  [ "$FAIL" -eq 0 ] || exit 1
  exit 0
fi

# ─────────────────────────────── 3. 앱 빌드 + Lint
say "앱 빌드 (:app:assembleDebug)"
if [ -x app/gradlew ] && [ -f app/settings.gradle.kts ]; then
  out="$(cd app && JAVA_HOME="$JAVA17" ./gradlew -q :app:assembleDebug 2>&1)"
  if printf '%s' "$out" | grep -qE 'FAILURE|error:|^e:'; then
    row "빌드" FAIL "$(printf '%s' "$out" | grep -E '^e:|error:' | head -3 | tr '\n' ' ')"
  else
    row "빌드" PASS "assembleDebug 성공"
  fi
else
  row "빌드" SKIP "app/ Gradle 프로젝트 없음"
fi

say "앱 단위 테스트 (AT-08, 대전)"
if [ -x app/gradlew ]; then
  out="$(cd app && JAVA_HOME="$JAVA17" ./gradlew -q :app:testDebugUnitTest 2>&1)"
  xml_fail=0; xml_total=0
  if ls app/app/build/test-results/testDebugUnitTest/*.xml >/dev/null 2>&1; then
    for f in app/app/build/test-results/testDebugUnitTest/*.xml; do
      t=$(sed -n 's/.*tests="\([0-9]*\)".*/\1/p' "$f" | head -1)
      fa=$(sed -n 's/.*failures="\([0-9]*\)".*/\1/p' "$f" | head -1)
      er=$(sed -n 's/.*errors="\([0-9]*\)".*/\1/p' "$f" | head -1)
      [ -n "$t" ] && xml_total=$((xml_total + t))
      xml_fail=$((xml_fail + ${fa:-0} + ${er:-0}))
    done
  fi
  if printf '%s' "$out" | grep -q 'FAILED'; then
    row "AT-08·대전" FAIL "console/XML 참조"
  elif [ "$xml_fail" -eq 0 ] && [ "$xml_total" -gt 0 ]; then
    row "AT-08·대전" PASS "테스트 ${xml_total}개 통과"
  elif [ "$xml_total" -gt 0 ]; then
    row "AT-08·대전" FAIL "${xml_fail}개 실패"
  else
    row "AT-08·대전" SKIP "테스트 결과 XML 없음"
  fi
else
  row "AT-08·대전" SKIP "app/ Gradle 프로젝트 없음"
fi

say "pleos_lint (PG-08)"
LINT="$ROOT/../pleos-connect-design-system/.claude/skills/pleos-connect-ui-review/scripts/pleos_lint.py"
if [ -f "$LINT" ] && [ -d app ]; then
  out="$(python3 "$LINT" app 2>&1)"
  # P0/P1/P2 건수 파싱. 스크립트 출력 형식이 바뀌면 이 부분을 고친다.
  p0=$(printf '%s' "$out" | grep -cE 'P0' || true)
  if printf '%s' "$out" | grep -qE 'P0|안전'; then
    row "PG-08" FAIL "lint P0 흔적 ${p0}건 — 원문 확인 필요"
  else
    row "PG-08" PASS "P0 0건"
  fi
  printf '%s\n' "$out" > docs/evidence/G3/pleos_lint.txt 2>/dev/null || true
else
  row "PG-08" SKIP "lint 스크립트 또는 app/ 없음"
fi

# ─────────────────────────────── 4. 정적 검사 (CQ-03, CQ-07, CQ-09, LY-06)
say "정적 검사"
SRC_PATHS=(app/src core/src server)
existing=()
for p in "${SRC_PATHS[@]}"; do [ -e "$p" ] && existing+=("$p"); done

if [ ${#existing[@]} -gt 0 ]; then
  # CQ-03: TODO·FIXME·@Ignore·!!
  hits="$(grep -rnE 'TODO|FIXME|@Ignore|@Disabled' "${existing[@]}" 2>/dev/null | grep -v '/build/' || true)"
  if [ -z "$hits" ]; then row "CQ-03" PASS "TODO·FIXME·@Ignore 0" ; else row "CQ-03" FAIL "$(printf '%s' "$hits" | wc -l | tr -d ' ')건"; fi

  # CQ-07: 실명·키
  # 실명·계정·키만 잡는다. 업스트림 도메인은 비밀값이 아니므로 제외한다.
  # 개인 홈 경로·API 키 형태만 잡는다(실명 자체는 저장소에 남기지 않는다).
  secret_hits="$(grep -rniE '/Users/[a-z0-9._-]+/|sk-[A-Za-z0-9]{16,}|HIVE_API_KEY *= *["'\'']' "${existing[@]}" docs 2>/dev/null | grep -v '/build/' | grep -v 'docs/plan/' || true)"
  if [ -z "$secret_hits" ]; then row "CQ-07(비밀값)" PASS "0건"; else row "CQ-07(비밀값)" FAIL "$(printf '%s' "$secret_hits" | head -3 | tr '\n' ' ')"; fi

  # LY-06: hex 색
  hex_hits="$(grep -rnE '#[0-9a-fA-F]{6}\b' app/src/main/res 2>/dev/null | grep -v 'pleos_colors.xml' || true)"
  if [ -z "$hex_hits" ]; then row "LY-06" PASS "hex 색 0"; else row "LY-06" FAIL "$(printf '%s' "$hex_hits" | wc -l | tr -d ' ')건"; fi
else
  row "CQ-03" SKIP "소스 없음"; row "CQ-07(비밀값)" SKIP "소스 없음"; row "LY-06" SKIP "소스 없음"
fi

# CQ-09: 제출 저장소에 docs/plan·research·.omc
if git rev-parse --git-dir >/dev/null 2>&1; then
  tracked="$(git ls-files | grep -E '^(docs/plan/|research/|\.omc/)' || true)"
  if [ -z "$tracked" ]; then row "CQ-09" PASS "docs/plan·research·.omc 미추적"; else row "CQ-09" FAIL "$(printf '%s' "$tracked" | head -3 | tr '\n' ' ')"; fi
else
  row "CQ-09" SKIP "git 저장소 아님"
fi

# ─────────────────────────────── 5. APK 크기·서명
say "APK (PF-07, D-14)"
APK="apk/해적단_MOCA_release.apk"
[ -f "$APK" ] || APK="app/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  mb=$(( $(stat -f%z "$APK") / 1048576 ))
  if [ "$mb" -le 30 ]; then row "PF-07" PASS "${mb}MB"; else row "PF-07" FAIL "${mb}MB (>30)"; fi
  if [ -x "$SDK/build-tools/36.0.0/apksigner" ]; then
    dn="$("$SDK/build-tools/36.0.0/apksigner" verify --print-certs "$APK" 2>/dev/null | grep -m1 'certificate DN' || true)"
    echo "$dn" | grep -q 'CN=해적단' && row "D-14(DN)" PASS "$dn" || row "D-14(DN)" FAIL "${dn:-서명 없음}"
  else
    row "D-14(DN)" SKIP "apksigner 없음"
  fi
else
  row "PF-07" SKIP "release APK 없음"; row "D-14(DN)" SKIP "release APK 없음"
fi

# ─────────────────────────────── 6. 에뮬레이터 (선택)
say "에뮬레이터 실행 (선택)"
ADB="$SDK/platform-tools/adb"
if [ -x "$ADB" ] && "$ADB" devices | grep -q 'device$'; then
  if "$ADB" shell pm list packages 2>/dev/null | grep -q 'ai.moca.app'; then
    row "설치" PASS "ai.moca.app 설치됨"
  else
    row "설치" FAIL "ai.moca.app 미설치"
  fi
else
  row "설치" SKIP "adb 기기 없음"
fi

# ─────────────────────────────── 요약
say "결과 요약"
printf '%-16s %-6s %s\n' "ID" "판정" "상세"
printf '%-16s %-6s %s\n' "----------------" "------" "----------------------------------------"
for r in "${ROWS[@]}"; do
  IFS='|' read -r id res detail <<< "$r"
  printf '%-16s %-6s %s\n' "$id" "$res" "$detail"
done
printf '\nPASS %d / FAIL %d / SKIP %d\n' "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ] || exit 1
