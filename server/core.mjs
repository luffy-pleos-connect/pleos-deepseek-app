import { createHash, timingSafeEqual } from 'node:crypto';
import seeds from './seeds.json' with { type: 'json' };
import {
  MAX_BODY_BYTES, empty, headerOf, json, readJson,
} from './http-util.mjs';
import { validateChat, validateDeviceId, validateSecret, validateSnapshot } from './validate.mjs';
import { adminPage } from './admin-page.mjs';

export const SNAPSHOT_TTL_DAYS = 90;
// 기본 업스트림 URL 은 환경변수로만 받는다(비밀값·도메인 하드코딩 금지).
export const HIVE_URL = '';
export const HIVE_MODEL = 'deepseek-ai/deepseek-v4.1-flash-1017';
export { MAX_BODY_BYTES };
// 추론형 모델은 추론에 쓴 토큰도 이 한도에 들어간다. 300 이면 답이 문장 중간에서 끊겼다(2026-11-12 실측 3회 중 2회).
export const MAX_TOKENS = 1000;

const HOUR = 3600;
const DAY = 86400;
const RATE = { chat: [30, HOUR], snapshots: [60, HOUR], opponent: [60, HOUR], events: [120, HOUR] };
const EVENT_TTL_DAYS = 100;
const STATS_RECENT = 200;
const TIMEOUT = Symbol('hive-timeout');
const TOO_LARGE = Symbol('too_large');
const KST_DAY = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
});

export const SEEDS = seeds;
export const SEED_POOL = seeds.filter((seed) => seed.demoOnly !== true);

const sha256 = (value) => createHash('sha256').update(value, 'utf8').digest('hex');

function hashEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

const EVENT_NAMES = new Set([
  'app_open', 'screen_view', 'inspection_done', 'problem_found', 'problem_resolved', 'fix_tap',
  'navi_opened', 'route_started', 'destination_arrived', 'product_tap', 'voice_ask', 'battle_done',
  'notification_shown', 'notification_tap', 'demo_on', 'demo_off', 'consent_changed', 'data_deleted',
]);
const SPLIT_PROP = { battle_done: 'result', screen_view: 'screen', voice_ask: 'intent' };
const MAX_EVENTS = 50;
const MAX_PROP_KEYS = 8;
const MAX_VOICE_MS = 120000;
const RANK_ANON_PER_HOUR = 2000;
const PROP_KEY_RE = /^[a-z][A-Za-z0-9]{0,19}$/;
const PROP_TEXT_RE = /^[A-Za-z0-9_.:-]{0,40}$/;

function propsProblem(props) {
  if (!isPlainObject(props)) return true;
  const keys = Object.keys(props);
  if (keys.length > MAX_PROP_KEYS) return true;
  for (const key of keys) {
    if (!PROP_KEY_RE.test(key)) return true;
    const value = props[key];
    if (typeof value === 'boolean') continue;
    if (typeof value === 'number' && Number.isFinite(value)) continue;
    if (typeof value === 'string' && PROP_TEXT_RE.test(value)) continue;
    return true;
  }
  return false;
}

function tokenEquals(provided, expected) {
  if (typeof provided !== 'string' || typeof expected !== 'string') return false;
  // 글자 수가 같아도 바이트 수가 다르면 timingSafeEqual 이 던진다. 해시끼리 비교한다.
  return hashEquals(sha256(provided), sha256(expected));
}

// 답은 화면과 음성에 그대로 나간다. 모델이 넣은 마크다운 표시(**굵게**, `코드`, # 제목, - 목록)는 뺀다.
// 2026-11-13 영상을 찍다가 화면에 「**ABS(빨강)**」이 그대로 보였다.
function plainText(text) {
  return text.replace(/\*+|__|`/g, '').replace(/^\s*#{1,6}\s+/gm, '').replace(/^\s*-\s+/gm, '');
}

// 한도에 닿아 끊긴 답은 마지막으로 끝난 문장까지만 남긴다. 끝난 문장이 없으면 그대로 둔다.
function dropUnfinishedSentence(text) {
  const end = Math.max(text.lastIndexOf('.'), text.lastIndexOf('!'), text.lastIndexOf('?'));
  return end > 0 ? text.slice(0, end + 1) : text;
}

const cardOf = (snapshot) => ({
  nickname: snapshot.nickname,
  grade: snapshot.grade,
  total: snapshot.total,
  categories: {
    power: snapshot.categories.power,
    brake: snapshot.categories.brake,
    tire: snapshot.categories.tire,
    electric: snapshot.categories.electric,
    safety: snapshot.categories.safety,
  },
});

const publicOpponent = (card, isSeed) => ({
  nickname: card.nickname,
  grade: card.grade,
  total: card.total,
  categories: card.categories,
  isSeed,
});

const kstDayKey = (now) => KST_DAY.format(new Date(now)).replaceAll('-', '');

const deviceKey = (deviceId) => sha256(`device:${deviceId}`).slice(0, 32);

const rateKey = (scope, deviceId) => `${scope}:dev:${deviceKey(deviceId)}`;

function normalizeHit(result) {
  if (result === true) return { allowed: true, resetAt: 0 };
  if (result === false) return { allowed: false, resetAt: 0 };
  if (!isPlainObject(result)) return { allowed: true, resetAt: 0 };
  return { allowed: result.allowed !== false, resetAt: Number(result.resetAt) || 0 };
}

function logLine(env, entry) {
  if (env && env.quiet) return;
  const write = env && typeof env.log === 'function' ? env.log : console.log;
  write(`[moca] ${entry.status} ${entry.ms}ms ${entry.method} ${entry.path}`);
}

async function enforce(store, scope, deviceId, env) {
  const [limit, windowSec] = RATE[scope];
  const hit = normalizeHit(await store.hit(rateKey(scope, deviceId), limit, windowSec));
  if (hit.allowed) return null;
  const clock = typeof env.now === 'function' ? env.now : Date.now;
  const headers = hit.resetAt > 0
    ? { 'retry-after': String(Math.max(1, Math.ceil((hit.resetAt - clock()) / 1000))) }
    : undefined;
  return json(429, { error: 'rate_limited' }, headers);
}

async function enforceDaily(store, env) {
  const configured = Number(env.DAILY_CHAT_LIMIT ?? 500);
  const limit = Number.isFinite(configured) && configured > 0 ? Math.floor(configured) : 500;
  const clock = typeof env.now === 'function' ? env.now : Date.now;
  const hit = normalizeHit(await store.hit(`chat:daily:${kstDayKey(clock())}`, limit, DAY));
  if (hit.allowed) return null;
  return json(429, { error: 'daily_limit' });
}

async function handleSnapshotPost(req, store, env) {
  const parsed = await readJson(req);
  if (parsed.tooLarge) return json(413, { error: 'too_large', field: 'body' });
  if (parsed.invalid) return json(400, { error: 'invalid_body', field: parsed.invalid });
  const input = parsed.value;
  if (!validateDeviceId(input.deviceId)) return json(400, { error: 'invalid_field', field: 'deviceId' });
  const limited = await enforce(store, 'snapshots', input.deviceId, env);
  if (limited) return limited;
  const bad = validateSnapshot(input);
  if (bad) return json(400, { error: 'invalid_field', field: bad });
  const secret = headerOf(req, 'x-device-secret');
  if (!validateSecret(secret)) {
    return json(400, { error: 'missing_secret', field: 'X-Device-Secret' });
  }
  const ttlDays = Number(env.SNAPSHOT_TTL_DAYS ?? SNAPSHOT_TTL_DAYS) || SNAPSHOT_TTL_DAYS;
  if (input.isDemo === true) {
    // 데모 요약은 저장하지 않는다(계획 v10 2.4). 검증은 그대로 하고 실제 요약을 덮어쓰지 않는다.
    return json(200, { ok: true });
  }
  const result = await store.putSnapshot(input.deviceId, {
    secretHash: sha256(secret),
    card: cardOf(input),
    isDemo: input.isDemo,
    inspectedAt: input.inspectedAt,
    ttlMs: ttlDays * DAY * 1000,
  });
  if (result && result.ok === false) {
    if (result.reason === 'secret_mismatch') return json(403, { error: 'secret_mismatch' });
    return json(500, { error: 'internal' });
  }
  return json(200, { ok: true });
}

async function handleOpponent(url, store, env) {
  const deviceId = url.searchParams.get('deviceId');
  if (!validateDeviceId(deviceId)) return json(400, { error: 'invalid_field', field: 'deviceId' });
  const limited = await enforce(store, 'opponent', deviceId, env);
  if (limited) return limited;
  let candidate = await store.randomOpponent(deviceId);
  if (candidate && (candidate.deviceId === deviceId || candidate.isDemo === true || !candidate.card)) {
    candidate = null;
  }
  const pool = [];
  if (candidate) pool.push({ card: candidate.card, isSeed: false });
  const seeds = Array.isArray(env.seedPool) ? env.seedPool : SEED_POOL;
  for (const seed of seeds) pool.push({ card: seed, isSeed: true });
  if (pool.length === 0) return json(404, { error: 'no_opponent' });
  const random = typeof env.random === 'function' ? env.random : Math.random;
  const index = Math.min(pool.length - 1, Math.max(0, Math.floor(random() * pool.length)));
  const picked = pool[index];
  return json(200, publicOpponent(picked.card, picked.isSeed));
}

// 모델 하나를 한 번 부른다. 대기 시간·끊긴 문장 처리는 모델과 상관없이 같으므로 여기 한 곳에 둔다.
// outerSignal 을 주면 바깥(겹쳐 부르기)에서 이 요청만 끊을 수 있다.
async function callHiveOnce(env, payload, fetchImpl, model, timeoutMs, outerSignal) {
  const apiKey = env.HIVE_API_KEY;
  if (typeof apiKey !== 'string' || apiKey.length === 0) return { status: 502 };
  const url = env.HIVE_API_URL || HIVE_URL;
  if (!url) return { status: 502 };
  const controller = new AbortController();
  const relayAbort = () => controller.abort();
  if (outerSignal) {
    if (outerSignal.aborted) controller.abort();
    else outerSignal.addEventListener('abort', relayAbort, { once: true });
  }
  let timer;
  // abort 로 끊긴 fetch 가 TIMEOUT 보다 먼저 거절되면 502 로 새지 않도록 시간 초과를 따로 기억한다.
  let timedOut = false;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
      reject(TIMEOUT);
    }, timeoutMs);
    if (typeof timer.unref === 'function') timer.unref();
  });
  const body = {
    model,
    messages: payload.messages,
    temperature: 0.3,
    max_tokens: MAX_TOKENS,
  };
  if (payload.tools) body.tools = payload.tools;
  try {
    const response = await Promise.race([
      fetchImpl(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
        body: JSON.stringify(body),
        signal: controller.signal,
      }),
      timeout,
    ]);
    if (!response || typeof response.text !== 'function' || !response.ok) return { status: 502 };
    const text = await Promise.race([response.text(), timeout]);
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      return { status: 502 };
    }
    const message = parsed?.choices?.[0]?.message;
    if (!message || typeof message !== 'object') return { status: 502 };

    // Hive(DeepSeek)는 도구 호출을 하면 content 가 비고 tool_calls 로 온다.
    // 추론형 모델은 답을 reasoning_content 에 넣고 content 를 비우기도 한다.
    // 앱은 message.tool_calls / message.content / message.reasoning_content 를 모두 읽는다.
    const toolCalls = Array.isArray(message.tool_calls) && message.tool_calls.length > 0
      ? message.tool_calls
      : null;
    // Hive 는 한도에 닿아 끊겨도 finish_reason 을 stop 으로 준다. 쓴 토큰 수로 판단한다.
    const cut = Number(parsed?.usage?.completion_tokens) >= MAX_TOKENS;
    const whole = typeof message.content === 'string' ? plainText(message.content) : '';
    const content = cut ? dropUnfinishedSentence(whole) : whole;
    const reasoning = typeof message.reasoning_content === 'string' ? message.reasoning_content : '';
    if (!toolCalls && content.length === 0 && reasoning.length === 0) return { status: 502 };

    return {
      status: 200,
      message: {
        role: message.role ?? 'assistant',
        content,
        reasoning_content: reasoning,
        tool_calls: toolCalls,
      },
    };
  } catch (error) {
    // abort 직후 fetch 가 먼저 거절해도 시간 초과로 센다.
    return { status: error === TIMEOUT || timedOut ? 504 : 502 };
  } finally {
    clearTimeout(timer);
    if (outerSignal) outerSignal.removeEventListener('abort', relayAbort);
  }
}

// 첫 모델이 느리면 두 번째 모델로 한 번 더 부른다. 두 번을 합쳐도 HIVE_TIMEOUT_MS 를 넘지 않는다.
const FIRST_TIMEOUT_MS_DEFAULT = 4000;

// HIVE_HEDGE_MS: 첫 모델을 기다리는 시간. 넘기면 첫 요청을 끊지 않은 채 두 번째 모델을 겹쳐 부르고,
// 먼저 성공한 답을 쓴다. 첫 모델이 그 전에 오류를 내면 기다리지 않고 바로 두 번째를 부른다.
function callHiveHedged(env, payload, fetchImpl, firstModel, fallbackModel, hedgeMs, totalMs) {
  return new Promise((resolve) => {
    const deadline = Date.now() + totalMs;
    const timers = [];
    const controllers = [];
    let done = false;
    let startedSecond = false;
    let failures = 0;
    let lastStatus = 502;

    const abortAll = () => {
      for (const controller of controllers) controller.abort();
    };
    const finish = (result) => {
      if (done) return;
      done = true;
      for (const timer of timers) clearTimeout(timer);
      abortAll();
      resolve(result);
    };
    const startSecond = () => {
      if (startedSecond || done) return;
      startedSecond = true;
      run(fallbackModel);
    };
    const run = (model) => {
      const controller = new AbortController();
      controllers.push(controller);
      const remaining = Math.max(1, deadline - Date.now());
      callHiveOnce(env, payload, fetchImpl, model, remaining, controller.signal).then((result) => {
        if (done) return;
        if (result.status === 200) {
          finish(result);
          return;
        }
        failures += 1;
        lastStatus = result.status;
        if (!startedSecond) {
          startSecond();
        } else if (failures >= 2) {
          finish({ status: lastStatus === 504 ? 504 : 502 });
        }
        // 한쪽이 오류여도 다른 쪽이 아직 진행 중이면 계속 기다린다.
      });
    };

    timers.push(setTimeout(() => finish({ status: 504 }), totalMs));
    timers.push(setTimeout(startSecond, Math.min(hedgeMs, totalMs)));
    run(firstModel);
  });
}

async function callHive(env, payload, fetchImpl) {
  if (typeof env.HIVE_API_KEY !== 'string' || env.HIVE_API_KEY.length === 0) return { status: 502 };
  if (!(env.HIVE_API_URL || HIVE_URL)) return { status: 502 };
  const firstModel = env.HIVE_MODEL || HIVE_MODEL;
  const totalMs = Number(env.HIVE_TIMEOUT_MS ?? 20000) || 20000;
  const fallbackModel = typeof env.HIVE_FALLBACK_MODEL === 'string' ? env.HIVE_FALLBACK_MODEL.trim() : '';
  // 두 번째 모델이 없거나 같은 모델이면 예전처럼 한 번만 부른다.
  if (fallbackModel === '' || fallbackModel === firstModel) {
    return callHiveOnce(env, payload, fetchImpl, firstModel, totalMs);
  }
  const hedgeRaw = env.HIVE_HEDGE_MS;
  const hedgeMs = hedgeRaw === undefined || hedgeRaw === null || hedgeRaw === '' ? null : Number(hedgeRaw);
  if (hedgeMs !== null && Number.isFinite(hedgeMs) && hedgeMs >= 0) {
    return callHiveHedged(env, payload, fetchImpl, firstModel, fallbackModel, hedgeMs, totalMs);
  }
  const firstMs = Number(env.HIVE_FIRST_TIMEOUT_MS ?? FIRST_TIMEOUT_MS_DEFAULT) || FIRST_TIMEOUT_MS_DEFAULT;
  const deadline = Date.now() + totalMs;
  const first = await callHiveOnce(env, payload, fetchImpl, firstModel, Math.min(firstMs, totalMs));
  if (first.status === 200) return first;
  const remaining = deadline - Date.now();
  if (remaining <= 0) return { status: 504 };
  return callHiveOnce(env, payload, fetchImpl, fallbackModel, remaining);
}

async function handleChat(req, store, env) {
  const parsed = await readJson(req);
  if (parsed.tooLarge) return json(413, { error: 'too_large', field: 'body' });
  if (parsed.invalid) return json(400, { error: 'invalid_body', field: parsed.invalid });
  const input = parsed.value;
  const bad = validateChat(input);
  if (bad) return json(400, { error: 'invalid_field', field: bad });
  const deviceId = headerOf(req, 'x-device-id') ?? input.deviceId ?? null;
  if (deviceId !== null && !validateDeviceId(deviceId)) {
    return json(400, { error: 'invalid_field', field: 'deviceId' });
  }
  if (deviceId !== null) {
    const limited = await enforce(store, 'chat', deviceId, env);
    if (limited) return limited;
  }
  const daily = await enforceDaily(store, env);
  if (daily) return daily;
  const fetchImpl = typeof env.fetch === 'function' ? env.fetch : globalThis.fetch;
  const result = await callHive(env, { messages: input.messages, tools: input.tools }, fetchImpl);
  if (result.status === 200) return json(200, { message: result.message });
  if (result.status === 504) return json(504, { error: 'upstream_timeout' });
  return json(502, { error: 'upstream_error' });
}

async function handleSnapshotDelete(url, req, store, env) {
  const deviceId = url.searchParams.get('deviceId');
  if (!validateDeviceId(deviceId)) return json(400, { error: 'invalid_field', field: 'deviceId' });
  const limited = await enforce(store, 'snapshots', deviceId, env);
  if (limited) return limited;
  const secret = headerOf(req, 'x-device-secret');
  if (!validateSecret(secret)) {
    return json(400, { error: 'missing_secret', field: 'X-Device-Secret' });
  }
  const result = await store.deleteSnapshot(deviceId, sha256(secret));
  if (result && result.ok === false) {
    if (result.reason === 'secret_mismatch') return json(403, { error: 'secret_mismatch' });
    return json(500, { error: 'internal' });
  }
  return empty(204);
}

async function handleEventsPost(req, store, env) {
  const parsed = await readJson(req);
  if (parsed.tooLarge) return json(413, { error: 'too_large', field: 'body' });
  if (parsed.invalid) return json(400, { error: 'invalid_body', field: parsed.invalid });
  const input = parsed.value;
  if (!validateDeviceId(input.deviceId)) return json(400, { error: 'invalid_field', field: 'deviceId' });
  const limited = await enforce(store, 'events', input.deviceId, env);
  if (limited) return limited;
  if (input.isDemo !== undefined && typeof input.isDemo !== 'boolean') {
    return json(400, { error: 'invalid_field', field: 'isDemo' });
  }
  const events = input.events;
  if (!Array.isArray(events) || events.length === 0 || events.length > MAX_EVENTS) {
    return json(400, { error: 'invalid_field', field: 'events' });
  }
  const counts = {};
  const bump = (name, value = 1) => {
    counts[name] = (counts[name] ?? 0) + value;
  };
  for (let i = 0; i < events.length; i += 1) {
    const event = events[i];
    if (!isPlainObject(event)) return json(400, { error: 'invalid_field', field: `events[${i}].name` });
    if (typeof event.name !== 'string' || !EVENT_NAMES.has(event.name)) {
      return json(400, { error: 'invalid_field', field: `events[${i}].name` });
    }
    const at = event.at;
    if (typeof at !== 'number' || !Number.isFinite(at) || at <= 0) {
      return json(400, { error: 'invalid_field', field: `events[${i}].at` });
    }
    const props = event.props;
    if (props !== undefined && propsProblem(props)) {
      return json(400, { error: 'invalid_field', field: `events[${i}].props` });
    }
    bump(event.name);
    // 이름별로 나눠 셀 속성: 대전은 결과, 화면 이동은 화면, 음성은 의도, 그 밖은 kind.
    const split = SPLIT_PROP[event.name] ?? 'kind';
    if (props && typeof props[split] === 'string' && props[split].length > 0) bump(`${event.name}|${props[split]}`);
    if (event.name === 'voice_ask') {
      bump(props && props.online === true ? 'voice_ask|online' : 'voice_ask|offline');
      const ms = props ? props.ms : undefined;
      if (typeof ms === 'number' && Number.isFinite(ms) && ms >= 0 && ms <= MAX_VOICE_MS) {
        bump('voice_ms_sum', Math.round(ms)); // Redis HINCRBY 는 정수만 받는다
        bump('voice_ms_n');
      }
    }
  }
  const clock = typeof env.now === 'function' ? env.now : Date.now;
  const at = clock();
  const day = kstDayKey(at);
  const key = deviceKey(input.deviceId);
  const rows = events.map((event) => {
    const row = { t: at, d: key.slice(0, 6), n: event.name };
    if (isPlainObject(event.props) && Object.keys(event.props).length > 0) row.p = event.props;
    return row;
  });
  await store.addEvents({
    day,
    scope: input.isDemo === true ? 'demo' : 'real',
    deviceKey: key,
    counts,
    rows,
    ttlSec: EVENT_TTL_DAYS * DAY,
  });
  return json(200, { ok: true, accepted: events.length });
}

function dayKeysBack(now, days) {
  const keys = [];
  for (let i = days - 1; i >= 0; i -= 1) keys.push(kstDayKey(now - i * DAY * 1000));
  return keys;
}

function positiveInt(value, fallback, min, max) {
  if (value === null || value === undefined || value === '') return fallback;
  if (!/^[0-9]+$/.test(value)) return null;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) return null;
  return parsed;
}

async function handleStats(url, req, store, env) {
  const expected = typeof env.ADMIN_TOKEN === 'string' ? env.ADMIN_TOKEN : '';
  if (expected.length === 0) return json(500, { error: 'not_configured' });
  const header = headerOf(req, 'authorization');
  const match = typeof header === 'string' ? /^Bearer (.+)$/.exec(header) : null;
  if (!match || !tokenEquals(match[1], expected)) return json(401, { error: 'unauthorized' });

  const days = positiveInt(url.searchParams.get('days'), 7, 1, 90);
  if (days === null) return json(400, { error: 'invalid_field', field: 'days' });
  const rawScope = url.searchParams.get('scope');
  const scope = rawScope === null || rawScope === '' ? 'real' : rawScope;
  if (scope !== 'real' && scope !== 'demo') return json(400, { error: 'invalid_field', field: 'scope' });

  const clock = typeof env.now === 'function' ? env.now : Date.now;
  const keys = dayKeysBack(clock(), days);
  const read = await store.readEvents({ days: keys, scope, recentLimit: STATS_RECENT });
  const dayRows = keys.map((day) => ({
    day,
    devices: read.days?.[day]?.devices ?? 0,
    counts: read.days?.[day]?.counts ?? {},
  }));
  const totals = {};
  for (const row of dayRows) {
    for (const [name, value] of Object.entries(row.counts)) totals[name] = (totals[name] ?? 0) + value;
  }
  const guide = totals.fix_tap ?? 0;
  const product = totals.product_tap ?? 0;
  return json(200, {
    scope,
    days: dayRows,
    totals,
    leads: { guide, product, total: guide + product },
    recent: Array.isArray(read.recent) ? read.recent : [],
  });
}

function html(body) {
  return new Response(body, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'content-security-policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}

// 페이지 자체에는 자료가 없다. 비밀번호를 넣으면 브라우저가 같은 폴더의 stats 를 부른다.
function handleAdmin() {
  return html(adminPage());
}

async function handleRank(url, store, env) {
  const rawTotal = url.searchParams.get('total');
  if (rawTotal === null || !/^[0-9]+(\.[0-9]+)?$/.test(rawTotal)) {
    return json(400, { error: 'invalid_field', field: 'total' });
  }
  const total = Number(rawTotal);
  if (!Number.isFinite(total) || total < 0 || total > 100) {
    return json(400, { error: 'invalid_field', field: 'total' });
  }
  const deviceId = url.searchParams.get('deviceId');
  if (deviceId !== null && !validateDeviceId(deviceId)) {
    return json(400, { error: 'invalid_field', field: 'deviceId' });
  }
  if (deviceId !== null) {
    const limited = await enforce(store, 'opponent', deviceId, env);
    if (limited) return limited;
  } else {
    // 기기 값 없이도 부를 수 있다. 저장소를 읽는 호출이므로 전체 횟수를 묶어 둔다.
    const hit = normalizeHit(await store.hit('rank:anon', RANK_ANON_PER_HOUR, HOUR));
    if (!hit.allowed) return json(429, { error: 'rate_limited' });
  }
  const seeds = Array.isArray(env.seedPool) ? env.seedPool : SEED_POOL;
  const real = (await store.realTotals(deviceId)).filter((value) => Number.isFinite(value));
  const higher = seeds.filter((seed) => seed.total > total).length
    + real.filter((value) => value > total).length;
  return json(200, {
    rank: 1 + higher,
    of: 1 + seeds.length + real.length,
    real: real.length,
  });
}

async function normalizedRequest(request) {
  if (request == null) return { method: 'GET', url: '/', headers: {}, body: null };
  const method = String(request.method ?? 'GET').toUpperCase();
  const url = String(request.url ?? '/');
  let body = request.body ?? null;
  if (body === null && typeof request.arrayBuffer === 'function' && method !== 'GET' && method !== 'HEAD') {
    const declared = Number(headerOf(request, 'content-length'));
    if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
      return { method, url, headers: request.headers, body: TOO_LARGE };
    }
    try {
      body = new Uint8Array(await request.arrayBuffer());
    } catch {
      body = new Uint8Array(0);
    }
  }
  return { method, url, headers: request.headers ?? {}, body };
}

export async function handle(request, store, env = {}) {
  const started = Date.now();
  const req = await normalizedRequest(request);
  const { method } = req;
  let url;
  try {
    url = new URL(req.url, 'http://moca.local');
  } catch {
    url = new URL('http://moca.local/');
  }
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (req.body === TOO_LARGE) {
    logLine(env, { status: 413, ms: Date.now() - started, method, path });
    return json(413, { error: 'too_large', field: 'body' });
  }
  let response;
  try {
    if (method === 'POST' && path === '/snapshots') response = await handleSnapshotPost(req, store, env);
    else if (method === 'DELETE' && path === '/snapshots') {
      response = await handleSnapshotDelete(url, req, store, env);
    } else if (method === 'GET' && path === '/opponent') response = await handleOpponent(url, store, env);
    else if (method === 'POST' && path === '/events') response = await handleEventsPost(req, store, env);
    else if (method === 'GET' && path === '/stats') response = await handleStats(url, req, store, env);
    else if (method === 'GET' && path === '/rank') response = await handleRank(url, store, env);
    else if (method === 'GET' && path === '/admin') response = handleAdmin();
    else if (method === 'POST' && path === '/chat') response = await handleChat(req, store, env);
    else if (['/snapshots', '/opponent', '/chat', '/events', '/stats', '/rank', '/admin'].includes(path)) {
      response = json(405, { error: 'method_not_allowed' });
    } else response = json(404, { error: 'not_found' });
  } catch {
    response = json(500, { error: 'internal' });
  }
  logLine(env, { status: response.status, ms: Date.now() - started, method, path });
  return response;
}

export async function handleStandard(request, store, env = {}) {
  const started = Date.now();
  const method = String(request?.method ?? 'GET').toUpperCase();
  const url = String(request?.url ?? '/');
  let path = '/';
  try {
    path = new URL(url, 'http://moca.local').pathname;
  } catch {
    path = '/';
  }
  if (method === 'GET' || method === 'HEAD') {
    return handle({ method, url, headers: request.headers, body: null }, store, env);
  }
  const declared = Number(headerOf(request, 'content-length'));
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    logLine(env, { status: 413, ms: Date.now() - started, method, path });
    return json(413, { error: 'too_large', field: 'body' });
  }
  const chunks = [];
  let total = 0;
  const stream = request.body;
  if (stream && typeof stream.getReader === 'function') {
    const reader = stream.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        try {
          await reader.cancel();
        } catch {
          // 취소 실패는 응답에 영향이 없다
        }
        logLine(env, { status: 413, ms: Date.now() - started, method, path });
        return json(413, { error: 'too_large', field: 'body' });
      }
      chunks.push(value);
    }
  } else if (typeof request.arrayBuffer === 'function') {
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.byteLength > MAX_BODY_BYTES) {
      logLine(env, { status: 413, ms: Date.now() - started, method, path });
      return json(413, { error: 'too_large', field: 'body' });
    }
    chunks.push(bytes);
    total = bytes.byteLength;
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return handle({ method, url, headers: request.headers ?? {}, body }, store, env);
}
