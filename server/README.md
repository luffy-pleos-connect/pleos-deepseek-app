# MOCA 서버

Node.js 24 표준 라이브러리만 쓴다. 외부 npm 패키지 0개, `package.json` 없음.

## 엔트리포인트

`core.mjs` 는 두 함수를 export 한다.

- **`handle(request, store, env) → Response`** — 기본 계약. `request` 는 `{method, url, headers, body}` 단순 객체이고,
  표준 `Request` 객체를 그대로 넘겨도 된다(`body` 가 없고 `arrayBuffer()` 가 있으면 읽어서 처리).
  `body` 는 문자열·`Buffer`/`Uint8Array`·`ArrayBuffer` 를 모두 받는다.
  `local.mjs` 와 Vercel `api/*.mjs` 는 이 함수를 쓴다.
- `handleStandard(Request, store, env) → Response` — 표준 `Request` 전용. 스트림 본문을 64KB까지만 읽고
  넘으면 즉시 413을 돌려준다. 표준 `Request` 의 `body` 를 한 번만 읽어야 하는 환경에서 쓴다.

`env` 는 `{HIVE_API_URL, HIVE_API_KEY, HIVE_MODEL, DAILY_CHAT_LIMIT, HIVE_TIMEOUT_MS, SNAPSHOT_TTL_DAYS, log, quiet, random, now, fetch}`.
테스트는 `log`·`random`·`now`·`fetch` 를 주입해 시각과 업스트림을 고정한다.

## 실행

```bash
node local.mjs                 # 127.0.0.1:8787, SQLite(moca.sqlite)
node --test core.test.mjs      # AT-07 (31 케이스)
node --test *.test.mjs         # core + store (62 케이스)
```

환경 변수: `PORT`, `HOST`, `MOCA_DB`, `HIVE_API_KEY`, `HIVE_API_URL`, `HIVE_MODEL`, `DAILY_CHAT_LIMIT`(기본 500),
`HIVE_TIMEOUT_MS`(기본 20000), `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`.

`HIVE_API_KEY` 는 요청 헤더로만 쓰고 로그·응답·저장소에 남기지 않는다. 로그는 `[moca] <상태> <지연>ms <메서드> <경로>` 형식뿐이다.

## API

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/snapshots` | `{deviceId, nickname, grade, total, categories, isDemo, inspectedAt}` + `X-Device-Secret` | 200 `{ok}` · 400 `{error, field}` · 403 · 413 · 429 |
| GET | `/opponent?deviceId=` | – | 200 `{nickname, grade, total, categories, isSeed}` · 400 · 404 · 429 |
| POST | `/chat` | `{messages, tools}` (+ 선택 `X-Device-Id`) | 200 `{message}` · 400 · 413 · 429 · 502 · 504 |
| DELETE | `/snapshots?deviceId=` | `X-Device-Secret` | 204 · 400 · 403 · 429 |

- 검증: 닉네임 `^[가-힣A-Za-z0-9]{2,10}$`, 등급 `S+ S A B C D`, 점수 0~100, `messages` ≤30(각 내용 ≤8000자), 본문 ≤65536 bytes.
- 기기 증명: 첫 POST 때 `X-Device-Secret` 의 SHA-256 해시를 저장한다. 같은 `deviceId` 의 upsert·DELETE 는 같은 비밀값이 필요하고 틀리면 403.
- 한도(고정 창): `/chat` 기기별 30회/시간, `/snapshots`·`/opponent` 기기별 60회/시간, `/chat` 하루 전체 `DAILY_CHAT_LIMIT`(기본 500, Asia/Seoul 날짜 기준).
- 스냅샷 TTL 90일(마지막 갱신 기준). 만료된 스냅샷은 조회·상대 풀에서 빠진다.
- `/opponent` 는 자기 자신과 `isDemo` 스냅샷을 제외하고, 남는 후보 + 시드 12명에서 균등 랜덤으로 고른다. 응답에 `deviceId` 가 없다.
- `/chat` 은 `https://api-cdn.thehive.ai/api/v3/chat/completions`, 모델 `deepseek-ai/deepseek-v4.1-flash-1017`,
  `temperature 0.3`, `max_tokens 300`, 20초 타임아웃(504). 키가 없으면 호출하지 않고 502.

## 저장소

모두 같은 인터페이스를 쓴다: `putSnapshot(deviceId, {secretHash, card, isDemo, inspectedAt, ttlMs})`,
`randomOpponent(deviceId)`, `hit(key, limit, windowSec)`, `deleteSnapshot(deviceId, secretHash)`, `getSnapshot(deviceId)`.

- `store-sqlite.mjs` (로컬) — `node:sqlite`, `moca.sqlite`. `close()` 제공.
- `store-redis.mjs` (Vercel) — Upstash REST `/pipeline` 을 `fetch` 로 호출. SDK 없음.
- `store-memory.mjs` (테스트·개발) — 만료·한도를 주입 시계(`now`)로 계산한다.

## 파일

`core.mjs` · `http-util.mjs` · `validate.mjs` · `seeds.json` · `store-{sqlite,redis,memory}.mjs` · `local.mjs` ·
`core.test.mjs` · `core-security.test.mjs` · `store.test.mjs` · `test-helpers.mjs`
