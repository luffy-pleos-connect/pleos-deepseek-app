# G1 서버 보고서 — MOCA 서버 (AT-07 · SEC-01~07)

작성일: 2026-11-10 · 담당: 서버 서브에이전트 · 작업 경로: `moca/server/`

## 결론

- `node --test core.test.mjs` **31개 전부 통과**(실패 0, 건너뜀 0).
- 저장소 3종까지 포함한 전체 스위트 `node --test *.test.mjs` **64개 전부 통과**.
- 외부 npm 패키지 0개. Node 24.19.0 표준 라이브러리(`node:http`, `node:sqlite`, `node:crypto`, 표준 `fetch`/`Request`/`Response`)만 사용.
- `local.mjs` 로 실제 HTTP 서버를 띄워 200/204/400/403/404/405/413/502를 curl로 확인했다(Hive 실제 호출은 하지 않음. 키 없음).

## 돌린 명령과 출력 요약

```bash
cd <repo>/moca/server
node --version            # v24.19.0
node --test core.test.mjs # tests 31 / pass 31 / fail 0 / skipped 0 / todo 0
node --test *.test.mjs    # tests 64 / pass 64 / fail 0 / skipped 0 / todo 0
```

실제 서버 스모크(`PORT=8791~8793`, `MOCA_DB=/tmp/...`, `HIVE_API_KEY=FAKE_KEY_FOR_LOG_CHECK`):

| 요청 | 결과 |
|---|---|
| `POST /snapshots` (정상) | 200 `{"ok":true}` |
| `POST /snapshots` (다른 비밀값 upsert) | 403 `{"error":"secret_mismatch"}` |
| `GET /opponent?deviceId=` | 200 `{nickname, grade, total, categories, isSeed}` — `deviceId` 없음 |
| `POST /chat` (키 없음) | 502 `{"error":"upstream_error"}` — 업스트림 호출 안 함 |
| `POST /snapshots` (70,000 bytes) | 413 `{"error":"too_large","field":"body"}` |
| `POST /snapshots` (빈 본문) | 400 `{"error":"invalid_body","field":"body"}` |
| `PATCH /snapshots` | 405 |
| `GET /nope` | 404 |
| `DELETE /snapshots` (다른 비밀값) | 403 |
| `DELETE /snapshots` (같은 비밀값) | 204 |
| 로그 누출 검사 `grep -cE "FAKE_KEY_FOR_LOG_CHECK\|smoke-device-0002\|smoke-secret-1234"` | 0 |

로그는 전부 `[moca] <상태> <지연>ms <메서드> <경로>` 형식이었다. 키·deviceId 원문·비밀값은 한 줄도 없다.

추가 확인: SQLite 파일을 닫았다 다시 열어도 스냅샷이 남고, 다른 비밀값 upsert는 여전히 `secret_mismatch`였다.

## 테스트 개수와 필수 항목 대응

`core.test.mjs` (AT-07, 31개)가 요구된 필수 항목을 모두 덮는다.

| 요구 항목 | 테스트 |
|---|---|
| upsert | 같은 비밀값 upsert 200, 첫 저장 200 |
| 상대에서 나·데모 제외 | 자기 자신 제외 / `isDemo` 제외 / 저장소가 데모·자기를 돌려줘도 걸러냄(방어 2건) |
| 시드 대체 | 상대 없음 → 시드, 다른 기기 스냅샷 → `isSeed:false` |
| 응답·로그에 키 없음 | `/chat` 응답 텍스트에 키 없음, 로그 전체 키·deviceId·비밀값 없음 |
| 400 | 닉네임·등급·점수·카테고리·필수 필드·본문 파싱·비밀값 누락·deviceId |
| 404 | 알 수 없는 경로 |
| 413 | `/snapshots` 64KB 초과, `/chat` 64KB 초과 |
| 429 | `/snapshots` 61번째, `/opponent` 61번째(+`retry-after`), `/chat` 기기별 31번째 |
| 502 | 키 없음(업스트림 미호출), fetch 예외, 업스트림 비정상 응답 |
| 504 | 20초 타임아웃 경로(`HIVE_TIMEOUT_MS=25`로 주입) |
| 하루 상한 | `DAILY_CHAT_LIMIT=2` 초과 429, Asia/Seoul 날짜 변경 시 초기화 |
| 비밀값 불일치 403 | upsert·DELETE 둘 다, 데이터 유지 확인 |
| 상대 응답에 deviceId 없음 | 응답 키 집합이 정확히 `categories, grade, isSeed, nickname, total` |
| 기타 | 스냅샷 90일 TTL, 시드 13명 계획 10장 표 일치, `handle`/`handleStandard` 양쪽 진입점 |

`core-security.test.mjs`(7개)는 하루 상한 초기화, `/chat` 413, 데모/자기 자신 방어, 상대 후보·시드 모두 없을 때 404, 429 `retry-after`를 다룬다.
`store.test.mjs`(26개)는 memory·sqlite·redis(가짜 Upstash) 세 저장소에 같은 계약(왕복·upsert·403·삭제·상대 제외·한도·TTL)을 돌리고, Upstash REST 파이프라인 형식·`EX 7776000`·`Bearer` 헤더를 확인한다.

## 미구현·한계

1. **Vercel `api/*.mjs` 미작성.** 계획 11장은 `api/*.mjs` 에서 `handle` 을 호출한다고만 정한다. 이번 작업 범위(server/ 디렉터리)에는 배포 어댑터를 만들지 않았다. `handleStandard` 가 표준 `Request`→`Response` 변환을 담당하므로 어댑터는 얇게 붙일 수 있다.
2. **Upstash 실서버 미검증.** 자격 증명이 없어 가짜 Upstash(pipeline 응답 형식 동일)로만 시험했다. 실제 `/pipeline` 응답 형식과 `EXPIRE ... NX` 지원 여부는 배포 시 1회 확인이 필요하다.
3. **Hive 실호출 미검증.** 키가 없어 실제 요청은 보내지 않았다. URL·모델·`temperature 0.3`·`max_tokens 300`·`Bearer` 헤더까지는 가짜 fetch 로 캡처해 고정했다. 실제 응답 스키마(`choices[0].message.content`)가 다르면 502가 된다.
4. **`/chat` 기기 식별은 선택.** 계약의 `/chat` 요청 본문에는 `deviceId` 가 없어 `X-Device-Id` 헤더(없으면 본문 `deviceId`)로 기기 한도를 건다. 헤더가 없는 호출은 하루 전체 상한에만 걸린다. 앱이 헤더를 보내도록 맞춰야 기기별 30회/시간이 실제로 동작한다.
5. **한도는 고정 창(fixed window)** 방식이다. 경계에서 최대 2배까지 통과할 수 있으나 계획의 "30회/시간"을 만족한다. 슬라이딩 창이 필요하면 저장소 `hit` 구현만 바꾸면 된다.
6. **`GET /opponent` 한도 추가.** 계획 11장은 `/opponent` 에 429를 명시하지만 회수를 정하지 않아 `/snapshots` 와 같은 60회/시간으로 정했다(자체 결정).
7. **`inspectedAt` 은 숫자(epoch ms)만 받는다.** 계획 11장이 Room 필드명만 정하고 형식을 정하지 않아 epoch ms 로 고정했다. 앱과 합의 필요.
8. **카테고리 키 이름**을 `power·brake·tire·electric·safety` 로 정했다(계획 8장의 카테고리 순서). 앱의 JSON 필드명과 일치해야 한다.
9. `thehive` 문자열은 `core.mjs` 의 `HIVE_URL` 상수 한 곳에만 있다. 테스트는 그 상수를 import 해 쓴다.

## 파일 목록

`moca/server/` (전부 400줄 이하)

| 파일 | 줄 | 역할 |
|---|---|---|
| `core.mjs` | 339 | `handle`·`handleStandard`, 라우팅, 한도, Hive 호출, 로그 |
| `http-util.mjs` | 79 | 헤더·JSON·본문 읽기(64KB 제한) |
| `validate.mjs` | 52 | 닉네임·등급·점수·메시지 검증 |
| `seeds.json` | 95 | 시드 13명(라이벌드라이버 포함) |
| `store-sqlite.mjs` | 168 | `node:sqlite` 저장소 |
| `store-redis.mjs` | 160 | Upstash REST 저장소 |
| `store-memory.mjs` | 98 | 인메모리 저장소 |
| `local.mjs` | 116 | `node:http` 127.0.0.1:8787 서버 |
| `core.test.mjs` | 355 | AT-07 (31개) |
| `core-security.test.mjs` | 111 | 보안 보강 (7개) |
| `store.test.mjs` | 239 | 저장소 계약 (26개) |
| `test-helpers.mjs` | 64 | 테스트 공용 도구 |
| `README.md` | 61 | 진입점 계약·API·실행법 |
