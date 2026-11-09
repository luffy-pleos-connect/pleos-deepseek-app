# MOCA :core 점수 엔진 — G1 증거

작성일: 2026-11-10 · 담당: core 서브에이전트 · 작업 위치: `moca/core/`

## 1. 돌린 명령

```bash
cd <repo>/moca/core
./run-tests.sh          # 컴파일 + 테스트 + 요약 (exit 0 = 통과)
```

통합 게이트에서도 확인:

```bash
cd <repo>/moca
./check.sh --quick      # AT-07(서버) + AT-01~06,10(core) → PASS 2 / FAIL 0 / SKIP 0
```

## 2. 결과

| 항목 | 값 |
|---|---|
| 테스트 총 개수 | **163** |
| 통과 | **163** |
| 실패 | **0** |
| 마지막 실행 결과 | `TOTAL=163 PASSED=163 FAILED=0` · `ALL TESTS PASSED` · exit 0 |

### 평가 기준 대응

| ID | 합격선 | 실제 | 파일 |
|---|---|---|---|
| AT-01 카테고리·건강 골든 | ≥40 | **46** | `ScoringTest.kt` |
| AT-02 관리 + 시즌 | ≥22 | **27** | `CareTest.kt` |
| AT-03 등급·RED 상한·건강 문턱·내림/올림·C-15/C-18 | ≥18 | **33** | `GradeTest.kt` |
| AT-04 대전 | ≥15 | **17** | `BattleTest.kt` |
| AT-05 속성 검사 (고정 시드, 10,000회) | 위반 0 | **11개 중 위반 0** | `PropertyTest.kt` |
| AT-06 시드 공식 일치 | 13/13 | **13/13** (10개 테스트) | `SeedsTest.kt` |
| AT-10 데모 D0~D6 + 고정 상대 | 전부 | **19/19** | `DemoHistoryTest.kt` |

AT-05가 도는 표본: 점수 범위/건강 범위/감점 단조성/종합 단조성/결정성/RED 상한/표시 내림·남은 점수 올림/등급 단조성/관리 범위/관리 결정성/대전 대칭 — 각 1,000~10,000회.

## 3. 빌드 방식

Android SDK·Gradle 없이 **JDK 17 + Kotlin 컴파일러 하나**로 돈다.

- Kotlin 컴파일러 탐색 순서: ① `kotlinc`(있으면) → ② Gradle 배포본에 들어 있는 `kotlin-compiler-embeddable` jar.
  현재 이 맥에서는 ②를 쓴다: `~/.gradle/wrapper/dists/gradle-9.9.0-bin/.../gradle-9.9.0/lib/kotlin-compiler-embeddable-2.4.21-min.jar` (+ 동봉 `kotlin-stdlib-2.4.21.jar`, coroutines/daemon/reflect/script-runtime/serialization jar).
- 컴파일은 `java -cp <compiler jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 ...` 로 직접 호출한다. 네트워크 접속 없음.
- JDK 17 경로: `JAVA_HOME_17` → `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` → 상속된 `JAVA_HOME` 순으로 찾는다(`check.sh`가 `JAVA_HOME`을 넘겨도 동작).
- 테스트는 JUnit 없이 자체 러너(`TestRunner.kt`, `@Test` 리플렉션)로 돌리고 `TOTAL/PASSED/FAILED`를 출력한다. `ALL TESTS PASSED` 문구는 `check.sh`가 인식하는 형식이다.
- 산출물은 `core/build/`(gitignore 대상)에만 생긴다.

## 4. 구현 요약

- `Model.kt` — Category(5)·Telltale·Item·FluidLevel·WheelArea·VehicleSnapshot·Inspection·Grade·Evaluation·BattleRound.
- `Rules.kt` — 감점 표(RED −50, YELLOW −25, 오일/브레이크액 LOW −40·CRITICALLY_LOW −50·HIGH −25·ERROR 측정 불가, 타이어 바퀴당 −20, 연료·배터리 <10% −10, 워셔액 −10, 에어백 −50), 원인 묶음(같은 묶음은 최대 감점 1개), 화이트리스트 밖 경고등 무시, RED 판정, 미지원 항목 제외.
- `Scoring.kt` — 카테고리 점수(0 바닥), 건강(측정된 카테고리 평균, 전부 null이면 null), 종합(건강×0.7+관리×0.3), 등급, RED 상한(최대 B), 건강 문턱(S는 건강 ≥90, S+는 ≥95, 아니면 A 상한), C-15/C-18 문구. 종합·남은 점수는 정수 분수 연산으로 계산해 부동소수 오차 없이 내림/올림한다.
- `Care.kt` — 시작 50, 점검일 +2(하루 1회), 7일 배수 +5, 해소 항목당 +5(하루 최대 10, 직전 점검 대비), 14일 초과 미점검 −1, 매 단계 0~100. 실제 프로필은 달력 월 시즌(1일 50 시작, 이번 달 기록만, 연속은 시즌 안에서만, 감쇠 기준일은 시즌 안 마지막 점검일 또는 시즌 시작일, 해소 보너스는 시즌 무관 직전 점검과 비교, 지난 시즌 등급 보존). 데모는 시즌 없음 → 날짜와 무관.
- `Battle.kt` — 동력→제동·조향→타이어→전기→안전 5라운드, 같거나 한쪽 null이면 무승부, 라운드 승수 → 종합 → 무승부.
- `Seeds.kt` + `resources/moca/core/seeds.json` — 13명(12명 대전 풀 + 데모 전용 라이벌드라이버). 표 숫자와 공식 재계산을 둘 다 테스트로 고정.
- `Demo.kt` — 오늘 기준 −40…−28일 격일 7회 정상 기록, D0~D6와 고정 상대, D3 ABS 해결 후 재점검 경로.

## 5. 미구현·한계

- 계획 12장은 테스트를 `app/src/test`에 두라고 했으나, Android SDK 없이 돌리기 위해 `core/src/test`로 분리했다(README 95행에 같은 이유 기록됨). `:app` 쪽에서 이 모듈을 의존성으로 물리는 작업은 이번 범위 밖이다.
- DB(Room) 매핑·서버 계약·UI 문구 리소스는 이번 범위 밖이다. C-15/C-18 문구는 `Scoring.HEALTH_NOTICE`와 `Evaluation.redCapNotice` 문자열로만 제공하고 `strings.xml`에는 넣지 않았다.
- 지난 시즌 등급은 `CareResult.previousSeasonGrade`로 계산해 주지만, 등급 상한(RED/건강 문턱)을 적용한 '지난 시즌 종합'까지 저장하는 모델은 없다(계획 7장은 등급만 요구).
- 데모 관리 53은 계획 표와 일치한다. 데모 기록은 '오늘'을 기준으로 만들므로 `Demo.ANCHOR`(2026-11-09)를 쓰지 않으면 날짜만 이동하고 점수는 같다(테스트로 확인).
- `check.sh`의 core 판정은 출력 문구(`ALL TESTS PASSED`) 기반이다. 러너 출력 형식을 바꾸면 `check.sh`도 함께 고쳐야 한다.
