# MOCA — 내 차를 키우는 차량 관리 파트너

Pleos Connect(현대차·기아·제네시스 차량용 인포테인먼트) 앱. 차가 직접 알려 주는 부품 상태를 점수와 등급으로 키우고, 음성으로 상담한다.

- 공모전: 현대차·기아 차량용 앱 개발 공모전
- 플랫폼: Pleos Connect (Android Automotive, API 28+)
- 언어·도구: Kotlin, Android View(XML), Java 17, AGP 9.5.1 / Gradle 9.9.0

> **정직 고지 (2026-11-10 기준)**
> Vehicle SDK 연동 코드를 구현했고, Pleos 에뮬레이터에서 **배터리 SOC 실값(100) 수신**과 **경고등 리스너 콜백 수신**을 로그로 확인했습니다(`docs/evidence/G0/g0-log.txt`).
> 다만 **경고등 색 정수(RED/YELLOW) 매핑은 에뮬레이터 Virtual Console의 DDS 브리지가 비활성이라 미확인**입니다. 그래서 경고등 관련 화면은 "연동 코드 구현, 실값 미확인" 상태이며, 데모 모드에서만 시나리오로 시연합니다. 자세한 내용은 `docs/evidence/G0/report.md` §7.

## 검증 (2026-11-10)

| 항목 | 결과 |
|---|---|
| 점수 엔진 단위 테스트 | **167 / 167** (`core/run-tests.sh`, JDK 17만) |
| 서버 테스트 | **65 / 65** (`server/node --test`) |
| 앱 단위 테스트 | **28 / 28** (`app/gradlew :app:testDebugUnitTest`) |
| Pleos 디자인 시스템 정적 검사 | **0 findings / 48 files** (`pleos_lint.py`) |
| 게이트 종합 | `./check.sh` → **PASS 12 / FAIL 0 / SKIP 0** |
| release APK | 4.17MB, DN `CN=해적단` |

## 공식 4항목

### 1. 앱 소개

MOCA는 차량이 제공하는 부품 상태를 읽어 **0~100점과 등급(S+~D)** 으로 보여 준다. 경고등이 켜지면 홈 배지가 바로 바뀌고, 무엇을 먼저 해결해야 하는지 "할 일"로 알려 준다. 관리 활동이 쌓이면 관리 점수가 오르고, 매달 시즌이 새로 시작된다.

### 2. 주요 기능

| 기능 | 설명 | Vehicle SDK |
|---|---|---|
| 부품 점검 | 카테고리 5개(동력·제동·타이어·전기·안전)를 읽어 점수·등급 계산 | `Safety`, `Tire`, `EvBattery` |
| 경고등 배지 | `registerTelltales` 리스너로 폴링 없이 즉시 갱신 | `Safety.registerTelltales` |
| 상태관리 | 카드 5개 + 원본값·감점 사유·조치 + 추이 | – |
| 음성 상담 | "엔진오일 괜찮아?" → 도구 호출 → 근거 있는 답 | 도구 8개 |
| 부품 대전 | 카테고리를 라운드로 겨루는 비동기 대전 | – |
| 데모 모드 | 실제 값이 아닌 시나리오. 모든 화면에 "데모 데이터" 표시 | – |

### 3. 기술 스택

| 영역 | 사용 |
|---|---|
| 차량 데이터 | `ai.pleos.playground:Vehicle:2.3.2` — 읽기 전용, `CONTROL_` 권한 0 |
| UI | `pleos-ds` (Pleos Connect Design System, View 기반) + `Theme.PleosConnect` |
| 서버 | Node.js 24 표준 라이브러리만. Vercel 함수 + Upstash Redis(REST) |
| AI | Hive `deepseek-ai/deepseek-v4.1-flash-1017`, OpenAI 호환 `/chat/completions`, 도구 호출 |
| 저장 | 기기 안 SQLite(Room), DataStore. 서버는 요약만 90일 |

### 4. 실행 방법

**앱 (에뮬레이터)**

```bash
# 1) Pleos 에뮬레이터 (창 없는 모드 — 이 맥에서 창 모드는 QEMU가 멈춘다)
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/emulator/emulator" -avd Pleos_Connect_v2.1.0 \
  -no-snapshot-load -gpu swiftshader_indirect -no-audio -no-window &

# 2) 앱 빌드·설치
cd app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n ai.moca.app/.MainActivity
```

**서버 (로컬)**

```bash
cd server
node local.mjs            # 127.0.0.1:8787, SQLite는 moca.sqlite
# 에뮬레이터에서는 10.0.2.2:8787
```

`HIVE_API_KEY` 는 환경변수로만 넣는다. 키가 없으면 `/chat` 은 502를 돌려주고, 앱은 **오프라인 음성**으로 전환한다.

```bash
HIVE_API_KEY=... node local.mjs
```

**테스트**

```bash
./check.sh          # 서버 테스트 + :core 테스트 + 린트 + 정적 검사
./check.sh --quick  # 서버·:core 만
cd server && node --test
cd core && ./run-tests.sh
```

## 구조

```
app/            Android 앱 (View + pleos-ds)
core/           점수 엔진 (순수 Kotlin/JVM, Android SDK 불필요)
server/         Node.js 서버 (표준 라이브러리만)
docs/
  evidence/     게이트별 실측 증거
  legal/        처리방침·이용약관
  submission/   기획서·별지·영상 대본
  ux/           배치·흐름 핸드오프
check.sh        품질 게이트
```

`:core` 를 분리한 이유: 점수 엔진을 Android SDK 없이 JDK 17만으로 테스트하기 위해서다. 계획서 12장은 `app/src/test` 에 두라고 했지만, 그러면 에뮬레이터·SDK 없이는 테스트를 못 돌린다.

## 점수 규칙 (요약)

- 카테고리: 100에서 원인 묶음별 감점을 뺀다. 같은 묶음은 가장 큰 감점 하나만. 미지원은 제외, 전부 미지원이면 `null`.
- 건강 = 지원 카테고리 평균. 종합 = 건강×0.7 + 관리×0.3.
- 등급: S+ ≥95 · S ≥90 · A ≥80 · B ≥70 · C ≥60 · D <60.
- 화이트리스트 RED 경고등이 켜져 있으면 등급 상한 B.
- **건강 문턱**: S는 건강 ≥90, S+는 건강 ≥95 일 때만. 아니면 A 상한.
- 관리: 시즌(달력 월)마다 50에서 시작. 점검한 날 +2, 7일 연속 +5, 해소 +5(하루 최대 +10), 14일 미점검 −1.
- 표시: 종합은 내림, 남은 점수는 올림.

## 데이터·개인정보

- VIN은 기기 밖으로 보내지 않는다. 서버로는 점수 요약만.
- 음성 질문 글자는 AI 응답에만 쓰고 서버에 저장하지 않는다.
- [개인정보 처리방침](docs/legal/개인정보처리방침.md) · [이용약관](docs/legal/이용약관.md)
- 설정 > 내 데이터 삭제 → 기기 기록 0건 + 서버 요약 삭제.

## 데모 모드

- 설정에서 데모 시나리오(D0~D6)를 고르면 실제 차량 값 대신 시나리오를 쓴다.
- 모든 데모 화면에 "데모 데이터" 배지가 붙는다.
- 데모 기록은 실제 기록과 분리되고, 시즌 계산에 들어가지 않는다.
- D3(ABS 빨강 + TPMS 노랑 + 저압 1개)가 대표 이야기다: 75점 B → ABS 해결 후 83점 A.

## 스크린샷

| 화면 | 파일 |
|---|---|
| 홈 (실차 값 B · 73) | `docs/evidence/G2/screens/home_final.png` |
| 점검 결과 | `docs/evidence/G2/screens/inspect.png` |
| 상태관리 | `docs/evidence/G2/screens/status_final.png` |
| 상세 시트(카드 펼침) | `docs/evidence/G2/screens/status_detail.png` |
| 첫 실행 | `docs/evidence/G2/screens/onboarding.png` |
| 야간 모드 | `docs/evidence/G3/windows/S17_land_night.png` |

## 알려진 제약

- **창 있는 에뮬레이터는 이 맥에서 부팅 직후 QEMU가 멈춘다.** `-no-window` + `swiftshader_indirect` 조합으로만 동작한다.
- **경고등 색 정수(RED/YELLOW) 매핑 미확인** — Virtual Console의 DDS 브리지가 비활성.
- **오일·브레이크액은 에뮬레이터 VHAL에 속성이 없다** → "측정 불가"로 처리.
- **세로 창 검수 미완** — `wm size` 오버라이드 후 윈도 서비스가 정지한다.
- **Hive 실호출 미검증** — `HIVE_API_KEY` 필요.
- 점수는 카테고리 5개 중 **3개 미만만 측정되면 등급을 내지 않는다**(정직성 규칙).

## 라이선스

- Asta Sans: SIL Open Font License 1.1 (`pleos-ds/FONT_LICENSE_OFL.txt`)
- Pleos Connect Design Guide의 내용과 저작권은 Pleos(42dot)에 있다.
