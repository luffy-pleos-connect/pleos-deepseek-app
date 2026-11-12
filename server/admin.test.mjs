// 계획 v10 2.5: 운영자 대시보드(/admin). lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './core.mjs';
import { createFetch } from './vercel-entry.mjs';
import { createMemoryStore } from './store-memory.mjs';
import { summarize } from './admin-view.mjs';
import { harness, req } from './test-helpers.mjs';

const IDS = [
  'tokenInput', 'tokenSubmit', 'scopeReal', 'scopeDemo', 'range1', 'range7', 'range30', 'updatedAt',
  'cardDevices', 'cardInspections', 'cardProblems', 'cardGuide', 'cardProduct', 'cardLeads',
  'dailyChart', 'funnel', 'kindTable', 'voiceStats', 'battleStats', 'recentTable', 'errorBox',
];

async function page() {
  const h = harness({ env: { ADMIN_TOKEN: 'admin-token-for-tests-0001' } });
  const res = await handle(req('GET', '/admin'), h.store, h.env);
  return { res, html: await res.text() };
}

test('GET /admin 은 한 파일짜리 HTML 을 돌려준다', async () => {
  const { res, html } = await page();
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/html; charset=utf-8');
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
  const csp = res.headers.get('content-security-policy') ?? '';
  for (const part of ["default-src 'none'", "connect-src 'self'", "frame-ancestors 'none'", "base-uri 'none'"]) {
    assert.ok(csp.includes(part), `CSP 에 ${part} 가 있어야 한다: ${csp}`);
  }
  assert.match(html, /^<!doctype html>/i);
  assert.match(html, /<html lang="ko"/);
  assert.match(html, /<meta name="viewport" content="width=device-width, initial-scale=1"/);
  assert.match(html, /<title>MOCA 운영<\/title>/);
});

test('페이지에는 정해진 자리(id)가 모두 있다', async () => {
  const { html } = await page();
  for (const id of IDS) assert.ok(html.includes(`id="${id}"`), `id="${id}" 가 없다`);
});

test('바깥 자원을 부르지 않고, 토큰이나 자료가 페이지에 들어 있지 않다', async () => {
  const { html } = await page();
  const urls = html.match(/https?:\/\/[^\s"'<>)]+/g) ?? [];
  assert.deepEqual(urls.filter((u) => u !== 'http://www.w3.org/2000/svg'), [], '외부 주소가 있으면 안 된다');
  assert.equal(html.includes('admin-token-for-tests-0001'), false);
  assert.equal(/<script[^>]+src=/i.test(html), false);
  assert.equal(/<link[^>]+href=/i.test(html), false);
});

test('자료는 같은 폴더의 stats 를 상대 경로로 부르고, 받은 글자를 HTML 로 넣지 않는다', async () => {
  const { html } = await page();
  assert.ok(/fetch\(\s*[`'"]stats\?/.test(html), "fetch('stats?…') 로 불러야 /admin 과 /api/admin 양쪽에서 된다");
  assert.equal(html.includes('/api/stats'), false);
  assert.equal(/innerHTML|insertAdjacentHTML|document\.write/.test(html), false, '받은 값은 textContent 로만 넣는다');
  assert.ok(html.includes('function summarize'), '페이지는 admin-view.mjs 의 summarize 를 그대로 싣는다');
  assert.ok(html.includes('prefers-color-scheme: dark'), '어두운 화면 색을 정의한다');
});

test('/admin 은 GET 만 받고, Vercel 에서는 /api/admin 으로 열린다', async () => {
  const h = harness();
  assert.equal((await handle(req('POST', '/admin', { body: {} }), h.store, h.env)).status, 405);
  const mod = await import('./api/admin.mjs');
  assert.equal(typeof mod.default.fetch, 'function');
  const fetch = createFetch({ env: {}, store: createMemoryStore() });
  const res = await fetch(new Request('https://example.invalid/api/admin'));
  assert.equal(res.status, 200);
  assert.match(await res.text(), /^<!doctype html>/i);
});

const STATS = {
  scope: 'real',
  days: [
    { day: '20261111', devices: 1, counts: { app_open: 2 } },
    {
      day: '20261112',
      devices: 3,
      counts: {
        app_open: 5, inspection_done: 4, problem_found: 3, 'problem_found|abs': 2, 'problem_found|tire_low': 1,
        fix_tap: 2, 'fix_tap|repair_shop': 1, 'fix_tap|charging': 1, navi_opened: 2, route_started: 1,
        destination_arrived: 0, problem_resolved: 1, 'problem_resolved|abs': 1, product_tap: 1, 'product_tap|washer': 1,
        voice_ask: 4, 'voice_ask|online': 3, 'voice_ask|offline': 1, voice_ms_sum: 7500, voice_ms_n: 3,
        battle_done: 2, 'battle_done|win': 1, 'battle_done|lose': 1,
      },
    },
  ],
  totals: {
    app_open: 7, inspection_done: 4, problem_found: 3, 'problem_found|abs': 2, 'problem_found|tire_low': 1,
    fix_tap: 2, 'fix_tap|repair_shop': 1, 'fix_tap|charging': 1, navi_opened: 2, route_started: 1,
    problem_resolved: 1, 'problem_resolved|abs': 1, product_tap: 1, 'product_tap|washer': 1,
    voice_ask: 4, 'voice_ask|online': 3, 'voice_ask|offline': 1, voice_ms_sum: 7500, voice_ms_n: 3,
    battle_done: 2, 'battle_done|win': 1, 'battle_done|lose': 1,
  },
  leads: { guide: 2, product: 1, total: 3 },
  recent: [{ t: 1794470000000, d: 'a1b2c3', n: 'fix_tap', p: { kind: 'repair_shop', source: 'home' } }],
};

test('summarize 는 통계를 화면에 쓸 값으로 바꾼다', () => {
  const view = summarize(STATS);
  assert.deepEqual(view.cards, { devices: 3, inspections: 4, problems: 3, guide: 2, product: 1, leads: 3 });
  assert.deepEqual(view.daily, [
    { day: '11/11', guide: 0, product: 0, opens: 2 },
    { day: '11/12', guide: 2, product: 1, opens: 5 },
  ]);
  assert.deepEqual(view.funnel, [
    { key: 'problem_found', label: '문제 발견', count: 3 },
    { key: 'fix_tap', label: '안내 누름', count: 2 },
    { key: 'navi_opened', label: '내비 열림', count: 2 },
    { key: 'route_started', label: '길안내 시작', count: 1 },
    { key: 'destination_arrived', label: '도착', count: 0 },
    { key: 'problem_resolved', label: '문제 해소', count: 1 },
  ]);
  assert.deepEqual(view.kinds, [
    { kind: 'abs', found: 2, guide: 0, product: 0, resolved: 1 },
    { kind: 'charging', found: 0, guide: 1, product: 0, resolved: 0 },
    { kind: 'repair_shop', found: 0, guide: 1, product: 0, resolved: 0 },
    { kind: 'tire_low', found: 1, guide: 0, product: 0, resolved: 0 },
    { kind: 'washer', found: 0, guide: 0, product: 1, resolved: 0 },
  ]);
  assert.deepEqual(view.voice, { asks: 4, online: 3, offline: 1, onlinePercent: 75, averageMs: 2500 });
  assert.deepEqual(view.battle, { total: 2, win: 1, draw: 0, lose: 1 });
});

test('summarize 는 빈 통계에서도 죽지 않고 0 으로 채운다', () => {
  const view = summarize({ scope: 'real', days: [{ day: '20261112', devices: 0, counts: {} }], totals: {}, leads: { guide: 0, product: 0, total: 0 }, recent: [] });
  assert.deepEqual(view.cards, { devices: 0, inspections: 0, problems: 0, guide: 0, product: 0, leads: 0 });
  assert.equal(view.funnel.every((step) => step.count === 0), true);
  assert.deepEqual(view.kinds, []);
  assert.deepEqual(view.voice, { asks: 0, online: 0, offline: 0, onlinePercent: 0, averageMs: 0 });
  assert.deepEqual(view.battle, { total: 0, win: 0, draw: 0, lose: 0 });
});
