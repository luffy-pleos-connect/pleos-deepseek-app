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

`env` 는 `{HIVE_API_URL, HIVE_API_KEY, HIVE_MODEL, HIVE_FALLBACK_MODEL, HIVE_FIRST_TIMEOUT_MS, DAILY_CHAT_LIMIT, HIVE_TIMEOUT_MS, SNAPSHOT_TTL_DAYS, ADMIN_TOKEN, log, quiet, random, now, fetch}`.
테스트는 `log`·`random`·`now`·`fetch` 를 주입해 시각과 업스트림을 고정한다.

## 실행

```bash
node local.mjs                 # 127.0.0.1:8787, SQLite(moca.sqlite)
node --test core.test.mjs      # AT-07 (35 케이스)
node --test *.test.mjs         # core + store + v10 + admin + fallback (115 케이스)
```

환경 변수: `PORT`, `HOST`, `MOCA_DB`, `HIVE_API_KEY`, `HIVE_API_URL`, `HIVE_MODEL`, `HIVE_FALLBACK_MODEL`,
`HIVE_FIRST_TIMEOUT_MS`(기본 4000), `DAILY_CHAT_LIMIT`(기본 500),
`HIVE_TIMEOUT_MS`(기본 20000), `ADMIN_TOKEN`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`.

`HIVE_API_KEY` 는 요청 헤더로만 쓰고 로그·응답·저장소에 남기지 않는다. 로그는 `[moca] <상태> <지연>ms <메서드> <경로>` 형식뿐이다.

## Vercel 에 올리기

이 폴더(`server/`)를 Vercel 프로젝트의 루트로 올린다. 프레임워크는 없다.

- `api/chat.mjs`, `api/opponent.mjs`, `api/snapshots.mjs`, `api/events.mjs`, `api/stats.mjs`, `api/rank.mjs`, `api/admin.mjs` 가 함수가 된다. 모두 `vercel-entry.mjs` 의 `createFetch()` 를 `export default { fetch }` 로 내보낸다(Vercel 문서의 fetch Web Standard 형식).
- 주소는 `/api/chat`, `/api/opponent`, `/api/snapshots`, `/api/events`, `/api/stats`, `/api/rank`, `/api/admin` 다. 진입부가 `/api` 를 떼고 `core.mjs` 로 넘긴다.
- 환경 변수: `HIVE_API_URL`, `HIVE_API_KEY`, `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN`, `ADMIN_TOKEN`. 선택: `HIVE_MODEL`, `HIVE_FALLBACK_MODEL`, `HIVE_FIRST_TIMEOUT_MS`, `DAILY_CHAT_LIMIT`, `HIVE_TIMEOUT_MS`. Vercel Marketplace 의 Upstash 연동은 저장소 값을 `KV_REST_API_URL`, `KV_REST_API_TOKEN` 이름으로 넣는다. 진입부는 두 이름을 다 받는다.
- 저장소 환경 변수가 없으면 모든 요청에 500 `{"error":"not_configured"}` 로 답한다.
- 앱의 release 빌드에는 배포한 주소에 `/api` 를 붙여 넣는다: `./gradlew :app:assembleRelease -Pmoca.serverBaseUrl=https://<프로젝트>.vercel.app/api`
- 2026-11-12 에 `https://moca-server.vercel.app` 로 올렸다(프로젝트 `moca-server`, 리전 `iad1`, Node 24). 올린 파일은 `api/*.mjs` 3개와 `core.mjs` · `http-util.mjs` · `validate.mjs` · `store-redis.mjs` · `vercel-entry.mjs` · `seeds.json` 이고, 배포된 파일의 SHA-1 이 저장소 파일과 같은 것을 확인했다.
- 저장소는 Vercel Marketplace 의 Upstash for Redis 무료 요금제(`moca-redis`, 리전 `iad1`)다. `HIVE_API_URL`, `HIVE_API_KEY` 도 넣었다.
- 올린 서버를 직접 불러 확인한 결과와 앱에서 확인한 결과는 `docs/evidence/V9/서버-연결-확인.md` 에 있다.

## API

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/snapshots` | `{deviceId, nickname, grade, total, categories, isDemo, inspectedAt}` + `X-Device-Secret` | 200 `{ok}` · 400 `{error, field}` · 403 · 413 · 429 |
| GET | `/opponent?deviceId=` | – | 200 `{nickname, grade, total, categories, isSeed}` · 400 · 404 · 429 |
| POST | `/chat` | `{messages, tools}` (+ 선택 `X-Device-Id`) | 200 `{message}` · 400 · 413 · 429 · 502 · 504 |
| DELETE | `/snapshots?deviceId=` | `X-Device-Secret` | 204 · 400 · 403 · 429 |
| POST | `/events` | `{deviceId, isDemo, events:[{name, at, props?}]}` | 200 `{ok, accepted}` · 400 `{error, field}` · 413 · 429 |
| GET | `/stats?days=7&scope=real\|demo` | `Authorization: Bearer <ADMIN_TOKEN>` | 200 `{scope, days, totals, leads, recent}` · 400 · 401 · 500 |
| GET | `/rank?total=&deviceId=` | – | 200 `{rank, of, real}` · 400 · 429 |
| GET | `/admin` | – | 200 `text/html` (운영자 대시보드) · 405 |

- 검증: 닉네임 `^[가-힣A-Za-z0-9]{2,10}$`, 등급 `S+ S A B C D`, 점수 0~100, `messages` ≤30(각 내용 ≤8000자), 본문 ≤65536 bytes.
- 기기 증명: 첫 POST 때 `X-Device-Secret` 의 SHA-256 해시를 저장한다. 같은 `deviceId` 의 upsert·DELETE 는 같은 비밀값이 필요하고 틀리면 403.
- 한도(고정 창): `/chat` 기기별 30회/시간, `/snapshots`·`/opponent` 기기별 60회/시간, `/chat` 하루 전체 `DAILY_CHAT_LIMIT`(기본 500, Asia/Seoul 날짜 기준).
- 스냅샷 TTL 90일(마지막 갱신 기준). 만료된 스냅샷은 조회·상대 풀에서 빠진다.
- `/opponent` 는 자기 자신과 `isDemo` 스냅샷을 제외하고, 남는 후보 + 시드 12명에서 균등 랜덤으로 고른다. 응답에 `deviceId` 가 없다.
- `/chat` 은 `https://api-cdn.thehive.ai/api/v3/chat/completions`, 모델 `deepseek-ai/deepseek-v4.1-flash-1017`,
  `temperature 0.3`, `max_tokens 1000`(추론 토큰 포함. 한도에 닿아 끊긴 답은 끝난 문장까지만 보낸다), 20초 타임아웃(504). 키가 없으면 호출하지 않고 502.
- `/chat` 의 두 번째 모델: `HIVE_FALLBACK_MODEL` 을 주면 첫 모델(`HIVE_MODEL`)은 `HIVE_FIRST_TIMEOUT_MS`(기본 4000ms)까지만 기다리고, 넘기면 그 요청을 abort 하고 두 번째 모델로 한 번 더 부른다. 첫 모델이 상태 코드 200 이 아니거나, 본문이 JSON 이 아니거나, 답이 비어도 두 번째 모델로 넘어간다. 두 번을 합쳐 `HIVE_TIMEOUT_MS`(기본 20000ms)를 넘으면 504, 두 번째도 오류면 502. `HIVE_FALLBACK_MODEL` 이 없거나 첫 모델과 같으면 예전처럼 한 번만 부른다. 토큰 한도·끊긴 문장 처리는 두 모델에 똑같이 적용한다.
  - 실측 배경(2026-11-12, 올린 서버): GLM 5.3 Flash 는 중앙 2.7초였지만 30번 중 6번이 8초 대기를 넘겨 504 였다. DeepSeek V4.1 Flash 는 4~7초로 느리지만 10번 모두 성공했다. 그래서 첫 모델을 4초까지만 기다리고 느린 모델로 넘긴다.
- `/events`: `events` 1~50개, 이름은 정해진 18개(`app_open` `screen_view` `inspection_done` `problem_found` `problem_resolved` `fix_tap` `navi_opened` `route_started` `destination_arrived` `product_tap` `voice_ask` `battle_done` `notification_shown` `notification_tap` `demo_on` `demo_off` `consent_changed` `data_deleted`)만 받는다. `at` 은 양의 epoch ms. `props` 는 선택이고 키 8개 이하, 키 `^[a-z][A-Za-z0-9]{0,19}$`, 값은 불리언·유한한 숫자·`^[A-Za-z0-9_.:-]{0,40}$` 글자(한글·공백 불가)다. 틀린 요청은 하나도 세지 않는다. 기기당 120회/시간.
- `/events` 저장: 서버 시각의 서울 날짜(`YYYYMMDD`)·범위(`real`/`demo`)별로 이름별 셈, `props.kind` 가 있으면 `name|kind` 도 셈, `voice_ask` 는 `voice_ask|online`/`voice_ask|offline` 과 `props.ms` 가 0~120000 일 때 `voice_ms_sum`·`voice_ms_n` 을 센다. 그날 쓴 기기 키(SHA-256 앞 32자) 집합과 최근 기록 500개를 범위별로 남기고 100일 뒤 만료한다.
- `/stats`: `ADMIN_TOKEN` 이 비면 500 `not_configured`, `Authorization: Bearer` 가 없거나 틀리면 401(비교는 `timingSafeEqual`). `days` 1~90(기본 7), `scope` `real`|`demo`(기본 `real`), 벗어나면 400. 기록이 없는 날도 `counts: {}` 로 넣고 오래된 날부터 돌려준다. `leads.guide` 는 `fix_tap` 합, `leads.product` 는 `product_tap` 합이다. `recent` 는 최신부터 200개.
- `/rank`: `total` 0~100(없거나 벗어나면 400 `total`). `rank = 1 + (나보다 높은 시드 수) + (나보다 높은 실제 요약 수)`, `of = 1 + 12 + real`. `real` 은 데모가 아닌 살아 있는 요약 수이고 `deviceId` 를 주면 그 기기는 뺀다. 점수는 저장하지 않는다. `deviceId` 가 있으면 `opponent` 한도(60회/시간)를 쓴다.
- 데모 요약(`isDemo: true`)은 검증만 하고 저장하지 않는다(200 `{ok:true}`). 실제 요약을 덮어쓰지 않고 순위에도 들어가지 않는다.
- `/admin`: 비밀번호(`ADMIN_TOKEN`)를 한 번 넣으면 쓰는 **한 파일짜리 HTML** 대시보드다. 바깥 스크립트·글꼴·그림을 쓰지 않고(모두 페이지 안에 있다), CSP 는 `default-src 'none'`, `connect-src 'self'` 다. GET 만 받고 나머지는 405. 페이지 자체에는 토큰도 자료도 없다 — 브라우저가 같은 폴더의 `/stats` 를 `fetch('stats?days=…&scope=…')` 로 부른다(로컬 `/admin` → `/stats`, Vercel `/api/admin` → `/api/stats`).
- `/admin` 화면: 범위(실제/데모)·기간(오늘/7일/30일), 요약 카드 6개(기기·점검·문제 발견·안내 누름·상품 누름·이어 준 건), 날짜별 막대(안내 누름 + 상품 누름), 단계별 막대(첫 단계 대비 비율), 문제 종류별 표, 음성(질문 수·서버 답 비율·평균 답 시간), 대전(수·승·무·패), 「앱에서 한 행동」의 표 세 개, 최근 기록 200줄. 30초마다 다시 읽고(`document.hidden` 이면 건너뛴다) `HH:MM:SS 에 읽음` 을 적는다. 받은 값은 모두 `textContent` 로 넣는다. 401 이면 비밀번호 칸으로 돌아가고, 그 밖의 실패는 상태 코드를 보이며 이전 값을 지우지 않는다. 토큰은 `sessionStorage` 에만 두고, `#token=` 으로 주면 `history.replaceState` 로 주소에서 지운다.
- `/admin` 의 「앱에서 한 행동」: 넓은 화면(900px 이상)에서 나란히, 좁은 화면에서 한 줄씩 놓이는 표 세 개다. ① 행동별 — `|` 가 없는 이름(단, `voice_ms_*` 는 뺀다)의 횟수, ② 화면별 — `screen_view|X` 의 화면별 횟수, ③ 질문 종류별 — `voice_ask|X` 에서 `online`·`offline` 을 뺀 질문 종류별 횟수. 모두 많은 순, 같으면 이름순이고 줄이 없으면 「아직 기록이 없어요」다. 행동·화면 이름 밑에는 한국어 뜻을 작게 붙이고(`admin-page.mjs` 의 `ACTION_KO`·`SCREEN_KO`), 최근 기록의 이름 칸에도 같은 뜻을 붙인다.
- `/admin` 의 색: `:root` 에 `color-scheme: light dark` 를 두어 밝은 화면에서도 브라우저 기본 색(스크롤 막대·입력 칸)이 어둡게 뒤집히지 않는다. 밝은 화면 대비는 본문 글자 17.96:1, 흐린 글자 6.08:1(카드 위 5.62:1), 강조색 5.82:1 로 모두 4.5:1 을 넘는다. 어두운 화면은 15.96:1 / 7.36:1 / 7.86:1 이다.
- `/admin` 의 값 변환은 `admin-view.mjs` 의 `summarize(stats)` 가 한다(순수 함수, 바깥 변수 없음). `admin-page.mjs` 가 그 소스를 `summarize.toString()` 으로 페이지 `<script>` 에 그대로 싣는다.

## 저장소

모두 같은 인터페이스를 쓴다: `putSnapshot(deviceId, {secretHash, card, isDemo, inspectedAt, ttlMs})`,
`randomOpponent(deviceId)`, `hit(key, limit, windowSec)`, `deleteSnapshot(deviceId, secretHash)`, `getSnapshot(deviceId)`,
`addEvents({day, scope, deviceKey, counts, rows, ttlSec})`, `readEvents({days, scope, recentLimit})`, `realTotals(excludeDeviceId)`.

- `store-sqlite.mjs` (로컬) — `node:sqlite`, `moca.sqlite`. `close()` 제공.
- `store-redis.mjs` (Vercel) — Upstash REST `/pipeline` 을 `fetch` 로 호출. SDK 없음.
- `store-memory.mjs` (테스트·개발) — 만료·한도를 주입 시계(`now`)로 계산한다.

`addEvents` 는 `counts`(이름→더할 수)를 날짜·범위별로 더하고, `rows`(최근 기록 객체)를 범위별 목록 앞에 넣어 500개까지만 남긴다. `deviceKey` 가 있으면 그날 쓴 기기로 센다.
`readEvents` 는 `{days: {YYYYMMDD: {counts, devices}}, recent: [...]}` 를 돌려준다(`recent` 는 최신부터, 요청한 `recentLimit` 까지).
`realTotals` 는 데모가 아닌 살아 있는 요약의 `total` 배열(최대 500개)을 돌려준다. 순위 계산은 `core.mjs` 가 한다.
Redis 저장소는 `HINCRBY`·`EXPIRE`·`SADD`·`LPUSH`·`LTRIM`·`HGETALL`·`SCARD`·`LRANGE`·`MGET` 을 쓴다. `realTotals` 는 `moca:pool` 에서 500개까지만 읽으므로 그보다 많으면 순위의 `real` 이 실제보다 작아진다.

## 파일

`core.mjs` · `http-util.mjs` · `validate.mjs` · `seeds.json` · `store-{sqlite,redis,memory}.mjs` · `local.mjs` ·
`core.test.mjs` · `core-security.test.mjs` · `store.test.mjs` · `test-helpers.mjs` · `v10.test.mjs` · `admin.test.mjs` · `admin2.test.mjs` · `fallback.test.mjs` ·
`admin-view.mjs` (대시보드 값 변환) · `admin-page.mjs` (대시보드 HTML)
