import { createHash, timingSafeEqual } from 'node:crypto';
import seeds from './seeds.json' with { type: 'json' };
import {
  MAX_BODY_BYTES, empty, headerOf, json, readJson,
} from './http-util.mjs';
import { validateChat, validateDeviceId, validateSecret, validateSnapshot } from './validate.mjs';

export const SNAPSHOT_TTL_DAYS = 90;
// 기본 업스트림 URL 은 환경변수로만 받는다(비밀값·도메인 하드코딩 금지).
export const HIVE_URL = '';
export const HIVE_MODEL = 'deepseek-ai/deepseek-v4.1-flash-1017';
export { MAX_BODY_BYTES };

const HOUR = 3600;
const DAY = 86400;
const RATE = { chat: [30, HOUR], snapshots: [60, HOUR], opponent: [60, HOUR] };
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

async function callHive(env, payload, fetchImpl) {
  const apiKey = env.HIVE_API_KEY;
  if (typeof apiKey !== 'string' || apiKey.length === 0) return { status: 502 };
  const url = env.HIVE_API_URL || HIVE_URL;
  if (!url) return json(502, { error: 'upstream_error' });
  const model = env.HIVE_MODEL || HIVE_MODEL;
  const timeoutMs = Number(env.HIVE_TIMEOUT_MS ?? 20000) || 20000;
  const controller = new AbortController();
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(TIMEOUT);
    }, timeoutMs);
    if (typeof timer.unref === 'function') timer.unref();
  });
  const body = {
    model,
    messages: payload.messages,
    temperature: 0.3,
    max_tokens: 300,
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
    const content = typeof message.content === 'string' ? message.content : '';
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
    return { status: error === TIMEOUT ? 504 : 502 };
  } finally {
    clearTimeout(timer);
  }
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
    else if (method === 'POST' && path === '/chat') response = await handleChat(req, store, env);
    else if (['/snapshots', '/opponent', '/chat'].includes(path)) {
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
