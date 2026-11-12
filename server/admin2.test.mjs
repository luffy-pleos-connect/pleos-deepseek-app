// 계획 v10 2.5 에 더하는 것: 행동별·화면별·질문 종류별 표. lead 가 쓴 테스트다. 워커는 고치지 않는다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './core.mjs';
import { summarize } from './admin-view.mjs';
import { harness, req } from './test-helpers.mjs';

const STATS = {
  scope: 'real',
  days: [{ day: '20261112', devices: 2, counts: {} }],
  totals: {
    app_open: 6, screen_view: 9, 'screen_view|home': 4, 'screen_view|voice': 3, 'screen_view|battle': 2,
    voice_ask: 5, 'voice_ask|online': 4, 'voice_ask|offline': 1, 'voice_ask|warnings': 3, 'voice_ask|grade': 1, 'voice_ask|free': 1,
    voice_ms_sum: 9000, voice_ms_n: 4, fix_tap: 2, 'fix_tap|repair_shop': 2, demo_on: 1, notification_shown: 2, notification_tap: 1,
  },
  leads: { guide: 2, product: 0, total: 2 },
  recent: [],
};

test('summarize 는 행동별·화면별·질문 종류별 횟수를 많은 순으로 준다', () => {
  const view = summarize(STATS);
  assert.deepEqual(view.actions, [
    { name: 'screen_view', count: 9 },
    { name: 'app_open', count: 6 },
    { name: 'voice_ask', count: 5 },
    { name: 'fix_tap', count: 2 },
    { name: 'notification_shown', count: 2 },
    { name: 'demo_on', count: 1 },
    { name: 'notification_tap', count: 1 },
  ]);
  assert.deepEqual(view.screens, [
    { screen: 'home', count: 4 },
    { screen: 'voice', count: 3 },
    { screen: 'battle', count: 2 },
  ]);
  // online·offline 은 질문 종류가 아니다
  assert.deepEqual(view.intents, [
    { intent: 'warnings', count: 3 },
    { intent: 'free', count: 1 },
    { intent: 'grade', count: 1 },
  ]);
});

test('빈 통계에서는 세 목록이 모두 비어 있다', () => {
  const view = summarize({ scope: 'real', days: [{ day: '20261112', devices: 0, counts: {} }], totals: {}, leads: { guide: 0, product: 0, total: 0 }, recent: [] });
  assert.deepEqual(view.actions, []);
  assert.deepEqual(view.screens, []);
  assert.deepEqual(view.intents, []);
});

test('페이지에 세 표의 자리와 밝은 화면 색이 있다', async () => {
  const h = harness();
  const html = await (await handle(req('GET', '/admin'), h.store, h.env)).text();
  for (const id of ['actionTable', 'screenTable', 'intentTable']) assert.ok(html.includes(`id="${id}"`), id);
  assert.ok(html.includes('color-scheme: light dark'), ':root 에 color-scheme: light dark 를 둔다');
});
