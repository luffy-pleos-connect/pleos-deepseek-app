// 계획 v10 2장: 행동 로그(/events), 운영자 통계(/stats), 순위(/rank), 데모 요약 저장 안 함.
// lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './core.mjs';
import { createFetch } from './vercel-entry.mjs';
import { createMemoryStore } from './store-memory.mjs';
import { DEV, OTHER, harness, postSnap, req, snapshot } from './test-helpers.mjs';

const NOON_KST = Date.UTC(2026, 10, 12, 3, 0, 0); // 서울 2026-11-12 12:00
const TOKEN = 'admin-token-for-tests-0001';

function fixed(overrides = {}) {
  const now = overrides.now ?? (() => NOON_KST);
  return harness({ now, env: { now, ADMIN_TOKEN: TOKEN, ...overrides.env } });
}

const postEvents = (h, body) => handle(req('POST', '/events', { body }), h.store, h.env);
const getStats = (h, query = '', token = TOKEN) =>
  handle(
    req('GET', `/stats${query}`, { headers: token === null ? {} : { authorization: `Bearer ${token}` } }),
    h.store,
    h.env,
  );
const getRank = (h, query) => handle(req('GET', `/rank${query}`), h.store, h.env);
const json = async (res) => JSON.parse(await res.text());
const ev = (name, props) => ({ name, at: NOON_KST, ...(props ? { props } : {}) });

test('POST /events 는 받은 개수를 돌려주고 /stats 에서 셈·이어 준 건·기기 수·최근 기록으로 보인다', async () => {
  const h = fixed();
  const res = await postEvents(h, {
    deviceId: DEV,
    isDemo: false,
    events: [
      ev('app_open'),
      ev('problem_found', { kind: 'abs' }),
      ev('fix_tap', { kind: 'repair_shop', source: 'home' }),
      ev('fix_tap', { kind: 'charging', source: 'status' }),
      ev('product_tap', { kind: 'washer', source: 'home' }),
    ],
  });
  assert.equal(res.status, 200);
  assert.deepEqual(await json(res), { ok: true, accepted: 5 });
  await postEvents(h, { deviceId: OTHER, isDemo: false, events: [ev('app_open')] });

  const stats = await getStats(h, '?days=3');
  assert.equal(stats.status, 200);
  const body = await json(stats);
  assert.equal(body.scope, 'real');
  assert.deepEqual(body.days.map((d) => d.day), ['20261110', '20261111', '20261112']);
  assert.deepEqual(body.days[0].counts, {});
  assert.equal(body.days[0].devices, 0);
  const today = body.days[2];
  assert.equal(today.devices, 2);
  assert.equal(today.counts.app_open, 2);
  assert.equal(today.counts.fix_tap, 2);
  assert.equal(today.counts['fix_tap|repair_shop'], 1);
  assert.equal(today.counts['fix_tap|charging'], 1);
  assert.equal(today.counts['problem_found|abs'], 1);
  assert.equal(body.totals.fix_tap, 2);
  assert.deepEqual(body.leads, { guide: 2, product: 1, total: 3 });
  assert.equal(body.recent.length, 6);
  const first = body.recent[0];
  assert.equal(typeof first.t, 'number');
  assert.match(first.d, /^[0-9a-f]{6}$/);
  assert.equal(first.n, 'app_open'); // 최신부터: 마지막에 온 OTHER 의 app_open
  const tap = body.recent.find((row) => row.n === 'product_tap');
  assert.deepEqual(tap.p, { kind: 'washer', source: 'home' });
  assert.equal(JSON.stringify(body).includes(DEV), false, '기기 ID 원문이 나가면 안 된다');
});

test('실제 기록과 데모 기록은 섞이지 않는다', async () => {
  const h = fixed();
  await postEvents(h, { deviceId: DEV, isDemo: true, events: [ev('fix_tap', { kind: 'repair_shop' })] });
  const real = await json(await getStats(h));
  assert.equal(real.days.length, 7);
  assert.deepEqual(real.leads, { guide: 0, product: 0, total: 0 });
  assert.deepEqual(real.recent, []);
  const demo = await json(await getStats(h, '?scope=demo'));
  assert.equal(demo.scope, 'demo');
  assert.deepEqual(demo.leads, { guide: 1, product: 0, total: 1 });
  assert.equal(demo.recent.length, 1);
});

test('voice_ask 는 서버 답·기기 답 수와 걸린 시간 합을 센다', async () => {
  const h = fixed();
  await postEvents(h, {
    deviceId: DEV,
    isDemo: false,
    events: [
      ev('voice_ask', { intent: 'warnings', online: true, ms: 2500 }),
      ev('voice_ask', { intent: 'grade', online: true, ms: 1500 }),
      ev('voice_ask', { intent: 'free', online: false, ms: 300 }),
      ev('voice_ask', { intent: 'free', online: true, ms: 999999 }),
    ],
  });
  const { totals } = await json(await getStats(h));
  assert.equal(totals.voice_ask, 4);
  assert.equal(totals['voice_ask|online'], 3);
  assert.equal(totals['voice_ask|offline'], 1);
  assert.equal(totals.voice_ms_sum, 4300);
  assert.equal(totals.voice_ms_n, 3);
});

test('POST /events 는 모르는 이름·사람이 쓴 글·너무 많은 개수를 받지 않는다', async () => {
  const h = fixed();
  const bad = async (body, field) => {
    const res = await postEvents(h, body);
    assert.equal(res.status, 400, JSON.stringify(body).slice(0, 80));
    assert.deepEqual(await json(res), { error: 'invalid_field', field });
  };
  await bad({ deviceId: 'x', isDemo: false, events: [ev('app_open')] }, 'deviceId');
  await bad({ deviceId: DEV, isDemo: false, events: [] }, 'events');
  await bad({ deviceId: DEV, isDemo: false, events: Array.from({ length: 51 }, () => ev('app_open')) }, 'events');
  await bad({ deviceId: DEV, isDemo: false, events: [ev('app_open'), ev('hack_me')] }, 'events[1].name');
  await bad({ deviceId: DEV, isDemo: false, events: [{ name: 'app_open', at: 'now' }] }, 'events[0].at');
  await bad({ deviceId: DEV, isDemo: false, events: [ev('voice_ask', { text: '내 차 어때' })] }, 'events[0].props');
  await bad({ deviceId: DEV, isDemo: false, events: [ev('voice_ask', { 'bad key': 1 })] }, 'events[0].props');
  await bad({ deviceId: DEV, isDemo: false, events: [ev('voice_ask', { nested: { a: 1 } })] }, 'events[0].props');
  const stats = await json(await getStats(h));
  assert.deepEqual(stats.totals, {}, '틀린 요청은 하나도 세지 않는다');
});

test('POST /events 는 기기당 한 시간 120번까지다', async () => {
  const h = fixed();
  for (let i = 0; i < 120; i += 1) {
    const res = await postEvents(h, { deviceId: DEV, isDemo: false, events: [ev('app_open')] });
    assert.equal(res.status, 200, `${i + 1}번째`);
  }
  const over = await postEvents(h, { deviceId: DEV, isDemo: false, events: [ev('app_open')] });
  assert.equal(over.status, 429);
});

test('GET /stats 는 토큰이 설정돼 있고 맞을 때만 답한다', async () => {
  const none = fixed({ env: { ADMIN_TOKEN: '' } });
  const unset = await getStats(none);
  assert.equal(unset.status, 500);
  assert.deepEqual(await json(unset), { error: 'not_configured' });

  const h = fixed();
  for (const token of [null, 'wrong-token', `${TOKEN}x`]) {
    const res = await getStats(h, '', token);
    assert.equal(res.status, 401, String(token));
    assert.deepEqual(await json(res), { error: 'unauthorized' });
  }
  assert.equal((await getStats(h)).status, 200);
  for (const query of ['?days=0', '?days=91', '?days=abc', '?scope=all']) {
    assert.equal((await getStats(h, query)).status, 400, query);
  }
});

test('GET /rank 는 시드 12명과 실제 요약 사이의 순위를 돌려준다', async () => {
  const h = fixed();
  assert.deepEqual(await json(await getRank(h, '?total=75')), { rank: 9, of: 13, real: 0 });
  assert.deepEqual(await json(await getRank(h, '?total=100')), { rank: 1, of: 13, real: 0 });

  assert.equal((await postSnap(h, snapshot({ deviceId: OTHER, total: 80 }))).status, 200);
  assert.deepEqual(await json(await getRank(h, `?total=75&deviceId=${DEV}`)), { rank: 10, of: 14, real: 1 });
  assert.deepEqual(await json(await getRank(h, '?total=85')), { rank: 6, of: 14, real: 1 });
  // 내 요약은 세지 않는다
  assert.deepEqual(await json(await getRank(h, `?total=75&deviceId=${OTHER}`)), { rank: 9, of: 13, real: 0 });

  for (const query of ['', '?total=', '?total=-1', '?total=101', '?total=abc', '?total=75&deviceId=x']) {
    const res = await getRank(h, query);
    assert.equal(res.status, 400, query);
  }
  assert.equal((await json(await getRank(h, '?total=abc'))).field, 'total');
});

test('데모 요약은 저장하지 않는다: 실제 요약을 덮어쓰지 않고 순위에도 들어가지 않는다', async () => {
  const h = fixed();
  assert.equal((await postSnap(h, snapshot({ total: 85.6 }))).status, 200);
  const demo = await postSnap(h, snapshot({ total: 40, grade: 'D', isDemo: true }));
  assert.equal(demo.status, 200);
  assert.deepEqual(await json(demo), { ok: true });
  const stored = await h.store.getSnapshot(DEV);
  assert.equal(stored.isDemo, false);
  assert.equal(stored.card.total, 85.6);

  const fresh = fixed();
  assert.equal((await postSnap(fresh, snapshot({ isDemo: true }))).status, 200);
  assert.equal(await fresh.store.getSnapshot(DEV), null);
  assert.deepEqual(await json(await getRank(fresh, '?total=75')), { rank: 9, of: 13, real: 0 });
  // 검증은 그대로 한다
  assert.equal((await postSnap(fresh, snapshot({ isDemo: true, nickname: '!' }))).status, 400);
});

test('Vercel 진입부: 새 함수 세 개가 있고 ADMIN_TOKEN 을 넘긴다', async () => {
  for (const name of ['events', 'stats', 'rank']) {
    const mod = await import(`./api/${name}.mjs`);
    assert.equal(typeof mod.default.fetch, 'function', name);
  }
  const fetch = createFetch({ env: { ADMIN_TOKEN: TOKEN }, store: createMemoryStore() });
  const ok = await fetch(
    new Request('https://example.invalid/api/stats', { headers: { authorization: `Bearer ${TOKEN}` } }),
  );
  assert.equal(ok.status, 200);
  const posted = await fetch(
    new Request('https://example.invalid/api/events', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ deviceId: DEV, isDemo: false, events: [ev('app_open')] }),
    }),
  );
  assert.equal(posted.status, 200);
  const rank = await fetch(new Request('https://example.invalid/api/rank?total=75'));
  assert.deepEqual(await rank.json(), { rank: 9, of: 13, real: 0 });
});
