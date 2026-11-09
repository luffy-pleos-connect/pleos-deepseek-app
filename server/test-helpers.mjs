import { handle } from './core.mjs';
import { createMemoryStore } from './store-memory.mjs';

export const SECRET = 'unit-test-secret-0001';
export const DEV = 'device-aaaa-0001';
export const OTHER = 'device-bbbb-0002';

export function req(method, path, { body, headers = {} } = {}) {
  return {
    method,
    url: `http://moca.test${path}`,
    headers,
    body: body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body),
  };
}

export function harness(overrides = {}) {
  const logs = [];
  const store = overrides.store ?? createMemoryStore({ now: overrides.now });
  const env = {
    log: (line) => logs.push(String(line)),
    DAILY_CHAT_LIMIT: '500',
    // 업스트림 URL 은 비밀값이 아니지만 소스에 하드코딩하지 않는다(환경변수로만).
    HIVE_API_URL: 'https://example.invalid/chat',
    ...overrides.env,
  };
  if (overrides.fetch) env.fetch = overrides.fetch;
  return { store, env, logs };
}

export function snapshot(overrides = {}) {
  return {
    deviceId: DEV,
    nickname: '테스트드라이버',
    grade: 'A',
    total: 85.6,
    categories: { power: 100, brake: 100, tire: 80, electric: 90, safety: 100 },
    isDemo: false,
    inspectedAt: Date.UTC(2026, 10, 9, 3, 0, 0),
    ...overrides,
  };
}

export const postSnap = (h, body, secret = SECRET) =>
  handle(req('POST', '/snapshots', { body, headers: { 'x-device-secret': secret } }), h.store, h.env);

export const deleteSnap = (h, deviceId = DEV, secret = SECRET) =>
  handle(
    req('DELETE', `/snapshots?deviceId=${deviceId}`, { headers: { 'x-device-secret': secret } }),
    h.store,
    h.env,
  );

export const getOpponent = (h, deviceId = DEV) =>
  handle(req('GET', `/opponent?deviceId=${deviceId}`), h.store, h.env);

export const postChat = (h, body, headers = {}) =>
  handle(req('POST', '/chat', { body, headers }), h.store, h.env);

export const okHive = (captured) => async (url, init) => {
  if (captured) captured.push({ url, init, body: JSON.parse(init.body) });
  return new Response(JSON.stringify({ choices: [{ message: { content: '지금은 괜찮아요' } }] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
};
