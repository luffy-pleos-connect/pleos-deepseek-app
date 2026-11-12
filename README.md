# MOCA — 시동만 걸면 차가 스스로 점검하는 차량 관리 파트너

시동만 걸면 차가 스스로 점검하고, 고칠 곳과 고칠 방법까지 이어 준다.

- 공모전: 현대차·기아 차량용 앱 개발 공모전
- 플랫폼: Pleos Connect (Android Automotive, API 28+)
- 언어·도구: Kotlin, Android View(XML), Java 17, AGP 9.5.1 / Gradle 9.9.0

> **정직 고지**
> 지금 숫자는 모두 이 저장소 안에서 실제로 돌린 결과다. 서버는 2026-11-12 에 `https://moca-server.vercel.app` 로 올렸고, **release APK 도 같은 날 그 주소를 넣어 다시 빌드**했다(`docs/evidence/V9/서버-연결-확인.md`). 실행해 보지 못한 것은 [알려진 제약](#알려진-제약)에 그대로 적었다.
> Vehicle SDK 는 Pleos 에뮬레이터에서 **배터리 SOC 실값(100) 수신**과 **경고등 리스너 콜백 수신**을 로그로 확인했다(`docs/evidence/G0/g0-log.txt`). **경고등 색 정수(빨강·노랑) 대응은 확인하지 못했다.** 그래서 경고등이 켜진 화면은 데모 모드의 시나리오로만 보여 준다(`docs/evidence/G0/report.md` §7).

## 검증 (2026-11-11)

| 항목 | 결과 |
|---|---|
| 점수 엔진 단위 테스트 | **238 / 238** (`core/run-tests.sh`, JDK 17만) |
| 서버 테스트 | **75 / 75** (`server` 에서 `node --test`) |
| 앱 단위 테스트 | **90 / 90** (`app/gradlew :app:testDebugUnitTest`) |
| 평가 도구 단위 테스트 | **48 / 48** (`python3 -m unittest tools/test_moca_eval.py`) |
| Pleos 디자인 시스템 정적 검사 | **0 findings / 80 files** (`pleos_lint.py`) |
| 게이트 종합 | `./check.sh` → **PASS 12 / FAIL 0 / SKIP 0** |
| release APK | 4.17MB, DN `CN=해적단` (2026-11-10 빌드. 그 뒤 변경은 들어 있지 않다) |

## 공모전 README 필수 항목

- ① 앱 이름 및 한 줄 소개 → 아래 「1. 앱 소개」
- ② 실행 방법 → 아래 「4. 실행 방법」
- ③ 테스트 환경 및 필요한 설정 → 아래 「테스트 환경 및 필요한 설정」
- ④ 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격 → 아래 「테스트 환경 및 필요한 설정」
- ⑤ APK 파일 위치 → 아래 「APK 위치」

### 1. 앱 소개

MOCA는 차량이 알려 주는 부품 상태를 읽어 **0~100점과 등급(S+~D)** 으로 보여 준다. 앱을 열면 점검은 이미 끝나 있다. 누르지 않는다. 문제가 있으면 「가장 먼저 할 일」 하나와 해결 단추 하나를 보여 주고, 정비소 안내나 휴대폰 QR 로 이어 준다. 관리 활동이 쌓이면 관리 점수가 오르고, 매달 시즌이 새로 시작된다.

### 2. 화면과 기능

아래 탭 다섯 개다. 「점검하기」 같은 별도 화면은 두지 않았다.

| 탭 | 내용 |
|---|---|
| 홈 | 등급과 종합 점수 고리, 「가장 먼저 할 일」 하나와 해결 단추 하나, 카테고리 5개의 상태(정상·주의·위험·측정 불가)와 점수, 오늘의 순위 |
| 상세 | 카테고리별 읽은 값, 감점, 조치 한 줄, 해결 단추, 최근 점검(점검한 날 기준 최대 14일)의 종합 점수 그래프. 저압 바퀴의 위치(앞왼쪽 등) |
| 차에게 묻기 | 마이크 또는 글자로 묻고 도구 8개로 차량 값을 읽어 답한다 |
| 순위·대전 | 카테고리 5개를 라운드로 겨루는 대전과 오늘의 순위 |
| 설정 | 내 정보·데모·데이터. 내 데이터 삭제는 확인 창을 거친다 |

기능별로는 이렇다.

1. **자동 점검** — 앱을 열 때 한 번 돈다. 누르지 않는다. 시동(부팅) 때도 화면 없이 한 번 돈다. 부팅 때 점검은 앱을 설치한 뒤 한 번 열어 동의를 마친 다음부터 된다.
2. **이어 주기** — 정비소·충전소·주유소는 내비게이션 검색 화면을 연다(NaviHelper `requestNavigationScreen(SearchPlace(검색어))`). 부품(워셔액)은 QR 로 휴대폰에 넘긴다(ZXing). 내비게이션 연결이 안 되면 QR 로 넘긴다. 주행 중에는 QR 을 열지 않는다. QR 주소는 제조사 공식 페이지다(`app/app/src/main/java/ai/moca/app/link/LinkTargets.kt`). 데모 D7(워셔액 부족)을 고르면 이 QR 을 눌러 볼 수 있다.
3. **오늘의 순위** — 앱 안의 연습 상대 12명과 오늘 점수로 매긴 순위다. 화면에 「연습 순위」라고 적는다. 실제 사용자 순위가 아니다.
4. **등급이 오르는 순간** — 지난번보다 등급이 오르면 홈에 「B → A · 9위 → 5위」가 한 번 나온다. 주행 중에는 나오지 않는다.
5. **데모 모드** — 시나리오 D0~D7. 모든 화면 위에 「데모 데이터」가 보인다.
6. **주행 중 제한** — 상세·순위·대전·설정 탭과 QR 을 막는다(`PleosUxRestrictions`).

### 3. 기술 스택

| 영역 | 사용 |
|---|---|
| 차량 데이터 | `ai.pleos.playground:Vehicle:2.3.2` — 읽기 전용, `CONTROL_` 권한 0 |
| 내비게이션 | `ai.pleos.playground:NaviHelper:3.0.5` — 검색 화면 열기 |
| QR | `com.google.zxing:core:3.5.3` |
| UI | `pleos-ds` (Pleos Connect Design System, View 기반) + `Theme.PleosConnect` |
| 서버 | Node.js 24 표준 라이브러리만. Vercel 함수 + Upstash Redis(REST) |
| AI | Hive `deepseek-ai/deepseek-v4.1-flash-1017`, OpenAI 호환 `/chat/completions`, 도구 호출 |
| 저장 | 기기 안 SharedPreferences. 점검 기록은 지난달 1일 이후 것만 둔다(줄 수로 자르지 않는다). 서버는 요약만 90일 |

권한은 PRIVATE_CAR_SAFETY, CAR_SAFETY, CAR_ENERGY, CAR_DRIVE, CAR_INFO, NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP, RECORD_AUDIO, INTERNET, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC 다.

### 4. 실행 방법

**앱 (에뮬레이터)**

```bash
# 1) Pleos 에뮬레이터 (창 없는 모드 — 이 맥에서 창 모드는 QEMU가 멈춘다)
export ANDROID_HOME="$HOME/Library/Android/sdk"
"$ANDROID_HOME/emulator/emulator" -avd Pleos_Connect_v2.1.0 \
  -no-snapshot-load -gpu host -no-audio -no-window &

# 2) 앱 빌드·설치
cd app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n ai.moca.app/.MainActivity
```

에뮬레이터에 release 서명 앱이 이미 깔려 있으면 debug APK 설치가 거부된다. 그때는 `adb uninstall ai.moca.app` 로 지우고 다시 설치한다.

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
./check.sh          # 서버 테스트 + :core 테스트 + 빌드 + 앱 테스트 + lint + 정적 검사
./check.sh --quick  # 서버·:core 만
cd server && node --test
cd core && ./run-tests.sh
python3 -m unittest tools/test_moca_eval.py
```

## 테스트 환경 및 필요한 설정

- macOS, JDK 17
- Android SDK: platform-tools, emulator, build-tools 36.0.0 (`ANDROID_HOME=$HOME/Library/Android/sdk`)
- Node.js 24 (서버)
- Python 3 (평가 도구 단위 테스트)
- 서버 주소 설정: `.env.example` 참고 (`.env` 에 실제 값, 커밋하지 않음). 자세한 실행 방법은 위 「4. 실행 방법」.

Pleos Connect 에뮬레이터 v2.1.0 (AVD `Pleos_Connect_v2.1.0`, Android API 34). 화면 규격 2560×1440, density 160.

에뮬레이터는 `-gpu host` 로 띄운다. `-gpu swiftshader_indirect` 로 띄우면 에뮬레이터의 주행 화면 앱이 되풀이해 죽어 터치가 앱에 전달되지 않는다(2026-11-11 실측).

## APK 위치

- `apk/해적단_MOCA_release.apk`

2026-11-10 빌드라 그 뒤의 변경(2026-11-11 화면 개편)이 들어 있지 않다. 다시 빌드해야 한다.

## 구조

```
app/            Android 앱 (View + pleos-ds)
core/           점수 엔진 (순수 Kotlin/JVM, Android SDK 불필요)
server/         Node.js 서버 (표준 라이브러리만)
tools/          평가 도구 (기능별 평가기준표 대조)
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
- 지원 카테고리가 5개 중 3개 미만만 측정되면 등급을 내지 않는다.
- 화이트리스트 RED 경고등이 켜져 있으면 등급 상한 B.
- **건강 문턱**: S는 건강 ≥90, S+는 건강 ≥95 일 때만. 아니면 A 상한.
- 관리: 시즌(달력 월)마다 50에서 시작. 점검한 날 +2, 7일 연속 +5, 해소 +5(하루 최대 +10), 14일 미점검 −1.
- 표시: 종합은 내림, 남은 점수는 올림.

## 데이터·개인정보

- VIN은 기기 밖으로 보내지 않는다. 서버로는 점수 요약만.
- 음성 질문 글자는 AI 응답에만 쓰고 서버에 저장하지 않는다.
- [개인정보 처리방침](docs/legal/개인정보처리방침.md) · [이용약관](docs/legal/이용약관.md)
- 설정 > 내 데이터 삭제 → 확인 창을 거친 뒤 기기 기록 0건 + 서버 요약 삭제.

## 데모 모드

- 설정에서 데모 시나리오(D0~D7)를 고르면 실제 차량 값 대신 시나리오를 쓴다.
- 모든 데모 화면에 「데모 데이터」 배지가 붙는다.
- 데모 기록은 실제 기록과 분리되고, 시즌 계산에 들어가지 않는다.
- D3(ABS 빨강 + TPMS 노랑 + 저압 1개)가 대표 이야기다: 75점 B → ABS 해결 후 83점 A.

## 스크린샷

`docs/evidence/V9/` 의 파일들이다.

| 화면 | 파일 |
|---|---|
| 홈 (D3) | `docs/evidence/V9/home-d3.png` |
| 홈 — 등급이 오른 순간 (D0) | `docs/evidence/V9/home-d0-등급이-오른-순간.png` |
| 상세 — 제동·조향 | `docs/evidence/V9/상세-제동조향.png` |
| 정비소 안내 검색 결과 | `docs/evidence/V9/정비소-안내-검색결과.png` |
| 순위·대전 | `docs/evidence/V9/순위-대전.png` |
| 설정 — 데모 | `docs/evidence/V9/설정-데모.png` |
| 첫 실행 | `docs/evidence/V9/첫-실행.png` |
| 내 데이터 삭제 확인 | `docs/evidence/V9/내-데이터-삭제-확인.png` |

부팅 때 점검의 logcat 은 `docs/evidence/V9/부팅-때-점검-logcat.txt` 에 있다. 내비게이션·부팅 점검의 시험 보고서는 `docs/evidence/S1/report.md`, `docs/evidence/S2/report.md`, `docs/evidence/S3/report.md` 다.

## 알려진 제약

- **주행 중 제한은 코드만 있고 실행해 보지 못했다.** 이 에뮬레이터에서는 주행 상태를 만들 수 없다.
- **음성은 글자 입력으로 확인했다.** 에뮬레이터에 마이크가 없다.
- **경고등 색 정수(빨강·노랑) 대응은 확인하지 못했다.** 에뮬레이터 Virtual Console 의 DDS 브리지가 비활성이다.
- **다른 사용자와의 대전은 서버에서만 확인했다.** 서버에 올린 요약이 다른 기기의 상대로 뽑히는 것은 직접 호출로 확인했다(40번 중 3번). 앱 화면에서는 보지 못했다. 이 에뮬레이터의 실제 값으로는 등급이 나오지 않고, 데모 모드는 정해진 상대와 겨루기 때문이다. 오늘의 순위는 앱 안의 연습 상대 12명과 매긴다.
- **서버 답은 5~11초 걸린다**(2026-11-12 에뮬레이터 6회). 서버가 답하지 못하면 기기 안의 답으로 넘어간다.
- **차의 제조사 값을 읽지 못해 정비 QR 은 현대 주소로 간다.**
- **오일·브레이크액은 에뮬레이터 VHAL에 속성이 없다** → 「측정 불가」로 처리.
- **서버의 횟수 제한(429)은 테스트로만 확인했다.** 올린 서버에서 한도까지 불러 보지는 않았다.

## 라이선스

제3자 자료와 라이선스는 [NOTICE.md](NOTICE.md)에 모아 두었다.

- Asta Sans: SIL Open Font License 1.1 (`pleos-ds/FONT_LICENSE_OFL.txt`)
- ZXing: Apache License 2.0
- Pleos Connect Design Guide의 내용과 저작권은 Pleos(42dot)에 있다.
