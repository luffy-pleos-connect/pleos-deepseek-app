import test from 'node:test';
import assert from 'node:assert/strict';
import { SEEDS } from './core.mjs';
import { createMemoryStore } from './store-memory.mjs';
import { DEV, OTHER, getOpponent, harness, okHive, postChat } from './test-helpers.mjs';

test('하루 상한은 Asia/Seoul 날짜가 바뀌면 초기화된다', async () => {
  let clock = Date.UTC(2026, 10, 9, 14, 0, 0);
  const h = harness({
    store: createMemoryStore({ now: () => clock }),
    env: { HIVE_API_KEY: 'k-1234', DAILY_CHAT_LIMIT: '1', now: () => clock },
    fetch: okHive(),
  });
  assert.equal((await postChat(h, { messages: [{ role: 'user', content: '1' }] })).status, 200);
  assert.equal((await postChat(h, { messages: [{ role: 'user', content: '2' }] })).status, 429);
  clock = Date.UTC(2026, 10, 9, 15, 30, 0);
  assert.equal((await postChat(h, { messages: [{ role: 'user', content: '3' }] })).status, 200);
});

test('POST /chat 본문 64KB 초과 → 413', async () => {
  const h = harness({ env: { HIVE_API_KEY: 'k-1234' }, fetch: okHive() });
  const res = await postChat(h, { messages: [{ role: 'user', content: 'x'.repeat(70000) }] });
  assert.equal(res.status, 413);
});

test('상대 후보에 데모가 섞여도 데모는 절대 반환되지 않는다', async () => {
  const picked = [];
  const store = {
    async putSnapshot() { return { ok: true }; },
    async getSnapshot() { return null; },
    async deleteSnapshot() { return { ok: true }; },
    async hit() { return { allowed: true, resetAt: 0 }; },
    async randomOpponent() {
      picked.push('demo');
      return {
        deviceId: OTHER,
        isDemo: true,
        card: { nickname: '데모상대', grade: 'A', total: 85, categories: {} },
      };
    },
  };
  const h = harness({ store });
  const res = await getOpponent(h, DEV);
  const body = await res.json();
  assert.equal(picked.length, 1);
  assert.notEqual(body.nickname, '데모상대');
  assert.equal(body.isSeed, true);
});

test('저장소가 자기 자신을 돌려줘도 상대에서 제외된다', async () => {
  const store = {
    async putSnapshot() { return { ok: true }; },
    async getSnapshot() { return null; },
    async deleteSnapshot() { return { ok: true }; },
    async hit() { return { allowed: true, resetAt: 0 }; },
    async randomOpponent(deviceId) {
      return { deviceId, isDemo: false, card: { nickname: '나자신', grade: 'A', total: 80, categories: {} } };
    },
  };
  const h = harness({ store });
  const body = await (await getOpponent(h, DEV)).json();
  assert.notEqual(body.nickname, '나자신');
  assert.equal(body.isSeed, true);
});

test('시드 13명이 계획 10장 표와 일치한다', () => {
  assert.equal(SEEDS.length, 13);
  const expected = {
    새벽드라이버: [100, 100, 100, 100, 100, 90, 97.0, 'S+'],
    주말캠퍼: [100, 100, 100, 100, 100, 75, 92.5, 'S'],
    출근길요정: [100, 100, 80, 100, 100, 80, 91.2, 'S'],
    달리는곰: [100, 100, 75, 100, 100, 60, 84.5, 'A'],
    세차는사랑: [100, 100, 100, 90, 100, 50, 83.6, 'A'],
    초보운전3년차: [60, 100, 100, 100, 100, 70, 85.4, 'A'],
    퇴근길질주: [60, 100, 75, 100, 100, 52, 76.5, 'B'],
    가족여행러: [100, 50, 100, 100, 100, 85, 88.5, 'B'],
    오일교체미루기: [60, 100, 100, 90, 90, 40, 73.6, 'B'],
    방치된세단: [60, 100, 60, 90, 100, 30, 66.4, 'C'],
    경고등수집가: [75, 75, 75, 100, 90, 20, 64.1, 'C'],
    주차장지킴이: [60, 60, 60, 75, 100, 10, 52.7, 'D'],
    라이벌드라이버: [100, 100, 100, 90, 90, 70, 88.2, 'A'],
  };
  for (const seed of SEEDS) {
    const row = expected[seed.nickname];
    assert.ok(row, `${seed.nickname} 이 계획 표에 없다`);
    const flat = [
      seed.categories.power, seed.categories.brake, seed.categories.tire,
      seed.categories.electric, seed.categories.safety, seed.management, seed.total, seed.grade,
    ];
    assert.deepEqual(flat, row, seed.nickname);
  }
});

test('상대 후보도 시드도 없으면 404', async () => {
  const store = createMemoryStore();
  const h = harness({ store, env: { seedPool: [] } });
  const res = await getOpponent(h, DEV);
  assert.equal(res.status, 404);
  assert.deepEqual(await res.json(), { error: 'no_opponent' });
});

test('/opponent 61번째 → 429 + retry-after 헤더', async () => {
  const h = harness();
  for (let i = 0; i < 60; i += 1) {
    const res = await getOpponent(h, DEV);
    assert.equal(res.status, 200);
  }
  const res = await getOpponent(h, DEV);
  assert.equal(res.status, 429);
  assert.ok(res.headers.get('retry-after'));
});
