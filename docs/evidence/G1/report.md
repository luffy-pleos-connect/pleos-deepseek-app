# G1 게이트 보고서 — 엔진·서버

- 시점: 2026-11-10 00:50 (계획 기한 11/11 12:00보다 이름)
- 판정자: 리드 (만든 서브에이전트와 다른 주체가 직접 명령을 돌려 확인)

## 판정

| 기준 | 합격선 | 측정 | 판정 | 증거 |
|---|---|---|---|---|
| AT-01 카테고리·건강 | ≥40 | **46** | 통과 | `core-report.md` |
| AT-02 관리·시즌 | ≥22 | **27** | 통과 | 〃 |
| AT-03 등급·RED 상한·건강 문턱 | ≥18 | **33** | 통과 | 〃 |
| AT-04 대전 | ≥15 | **17** | 통과 | 〃 |
| AT-05 속성 검사(10,000회) | 위반 0 | **위반 0** | 통과 | 〃 |
| AT-06 시드 | 13/13 | **13/13** | 통과 | 〃 |
| AT-10 데모 | 전부 | **19/19** | 통과 | 〃 |
| AT-07 서버 core | ≥22 | **32** (테스트 65개 중) | 통과 | `server-report.md` |
| CQ-03 TODO·FIXME·@Ignore | 0 | **0** | 통과 | `check.sh` 출력 |
| CQ-04 파일당 ≤400줄 | 위반 0 | 최대 355줄 | 통과 | `wc -l` |
| CQ-07 비밀값·실명 | 0 | **0** | 통과 | `check.sh` |
| CQ-09 제출 저장소 제외 | 0 | **0** | 통과 | `check.sh` |

**`:core` 테스트 총 163개 전부 통과** (직접 실행 확인: `TOTAL=163 PASSED=163 FAILED=0`).

## 돌린 명령과 출력

```bash
# 서버
cd moca/server && node --test
# → tests 65 / pass 65 / fail 0 / skipped 0 / todo 0

# 점수 엔진 (JDK 17 + Kotlin 컴파일러, Android SDK 불필요)
cd moca/core && ./run-tests.sh
# → OK BattleTest 17/17, CareTest 27/27, DemoHistoryTest 19/19,
#    GradeTest 33/33, PropertyTest 11/11, ScoringTest 46/46, SeedsTest 10/10
# → TOTAL=163 PASSED=163 FAILED=0 / ALL TESTS PASSED / RESULT: PASS

# 전체 게이트
cd moca && ./check.sh
# → PASS 9 / FAIL 0 / SKIP 2  (SKIP: release APK·서명 DN — G4 항목)
```

## 서버 실제 HTTP 스모크 (curl, 로컬 8787)

| 요청 | 결과 |
|---|---|
| `POST /snapshots` 정상 | 200 `{"ok":true}` |
| 같은 deviceId + 다른 비밀값 upsert | **403** `{"error":"secret_mismatch"}` |
| 같은 deviceId + 같은 비밀값 upsert | 200 |
| `GET /opponent?deviceId=` (다른 기기) | 200 시드 응답, **deviceId 필드 없음** |
| 70,000 bytes 본문 | **413** |
| `DELETE /snapshots` 다른 비밀값 | **403** |
| `DELETE /snapshots` 같은 비밀값 | **204** |
| `POST /chat` 키 없음 | 502 (업스트림 미호출) |

로그는 `[moca] <상태> <지연>ms <메서드> <경로>` 형식만 남았고 키·기기 ID 원문·비밀값은 0건이었다.

## 구조 결정 기록 (계획과 다른 점)

1. **`:core` 를 별도 JVM 모듈로 분리.** 계획 12장은 `app/src/test` 에 두라고 했으나, 그러면 Android SDK·에뮬레이터 없이는 테스트를 못 돌린다. G1 게이트를 SDK 없이 통과시키려고 분리했다.
2. **`:core` 빌드에 Gradle Kotlin 플러그인을 쓰지 않는다.** 로컬 Gradle 캐시에 Kotlin 플러그인이 없어 네트워크 없이 못 쓴다. 대신 `java -cp kotlin-compiler-embeddable-2.4.21.jar K2JVMCompiler` 로 직접 컴파일하고, JUnit 없이 자체 리플렉션 러너로 돌린다.
3. **앱은 `:core` 소스를 `sourceSets["main"].kotlin.srcDir` 로 직접 컴파일한다.** 별도 Gradle 모듈 의존성보다 단순하고, AGP 9 내장 Kotlin이 그대로 처리한다.
4. **업스트림 도메인을 소스에 하드코딩하지 않는다.** `HIVE_API_URL` 환경변수로만 받는다. 없으면 502. (check.sh CQ-07이 잡아서 고쳤다.)

## 미달·미구현 (정직 기록)

| 항목 | 상태 |
|---|---|
| Hive 실호출 | **미검증**. `HIVE_API_KEY` 가 없어 가짜 fetch로만 시험했다. |
| Upstash 실서버 | **미검증**. 자격 증명 없음. 동일 형식의 가짜 서버로만. |
| Vercel 배포 | **미완료**. `api/index.mjs` + `vercel.json` 은 작성했고 로드·실패 경로를 확인했다. 계정·환경변수는 사람 몫. |
| `/chat` 기기 한도 | `X-Device-Id` 헤더 기반(선택). 앱이 헤더를 보내야 30회/시간이 실제로 동작한다. **앱 미연결.** |
| CQ-02 커버리지 | **미측정**. JaCoCo 를 쓰지 않는 자체 러너라 줄·분기 커버리지를 재지 않는다. |

## 다음 게이트로 넘기는 것

- 앱 화면(F-01·02·04·05·07·08)은 G2 항목이지만, 이번에 홈·점검·상태관리를 실차 값으로 먼저 붙였다(`docs/evidence/G2/app-report.md`).
- 등급 계산이 앱에 연결됐다: 실측 **B · 73 (건강 84, 관리 50), "A까지 7점"**.
