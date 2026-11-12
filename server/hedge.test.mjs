// 계획 v10 4장: 두 모델을 겹쳐 부르기. lead 가 쓴 테스트다. 워커는 고치지 않는다.
// 배경(2026-11-12 20:40, 올린 서버 30회): 「4초 기다렸다가 두 번째 모델」은 29번 성공했지만 12번이 8~12초 걸렸다.
// 첫 모델을 버리지 않고 두 번째를 겹쳐 부르면 느린 경우가 「겹치는 시간 + 두 번째 모델 시간」으로 줄어든다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { harness, postChat } from './test-helpers.mjs';

const HIVE_URL = 'https://example.invalid/chat';
const BODY = { messages: [{ role: 'user', content: '경고등 뭐야' }] };
const ok = (text) => new Response(JSON.stringify({ choices: [{ message: { content: text } }] }), { status: 200 });
const env = (extra = {}) => ({
  HIVE_API_KEY: 'k', HIVE_API_URL: HIVE_URL, HIVE_MODEL: 'fast-model', HIVE_FALLBACK_MODEL: 'steady-model',
  HIVE_HEDGE_MS: '50', HIVE_TIMEOUT_MS: '600', ...extra,
});

// plan: { 'fast-model': 답할 때까지 ms | null(끝까지 답 없음) | 'error', ... }
function scripted(plan, log) {
  return (url, init) => {
    const model = JSON.parse(init.body).model;
    const entry = { model, at: Date.now(), aborted: false };
    log.push(entry);
    init.signal.addEventListener('abort', () => { entry.aborted = true; });
    const after = plan[model];
    if (after === 'error') return Promise.resolve(new Response('rate limited', { status: 429 }));
    return new Promise((resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new Error('aborted')));
      if (after !== null) setTimeout(() => resolve(ok(`${model} 의 답이에요.`)), after);
    });
  };
}
const answer = async (res) => JSON.parse(await res.text()).message.content;

test('첫 모델이 겹치는 시간 안에 답하면 두 번째 모델을 부르지 않는다', async () => {
  const log = [];
  const res = await postChat(harness({ env: env(), fetch: scripted({ 'fast-model': 10, 'steady-model': 10 }, log) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(await answer(res), 'fast-model 의 답이에요.');
  assert.deepEqual(log.map((c) => c.model), ['fast-model']);
});

test('첫 모델이 늦어지면 두 번째를 겹쳐 부르고, 먼저 온 답을 쓴다: 첫 모델이 먼저 온 경우', async () => {
  const log = [];
  const started = Date.now();
  const res = await postChat(harness({ env: env(), fetch: scripted({ 'fast-model': 120, 'steady-model': 300 }, log) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(await answer(res), 'fast-model 의 답이에요.');
  assert.deepEqual(log.map((c) => c.model), ['fast-model', 'steady-model']);
  assert.ok(log[1].at - log[0].at >= 40, '두 번째는 겹치는 시간(50ms) 뒤에 시작한다');
  assert.ok(Date.now() - started < 260, '첫 모델의 답(120ms)을 기다리지 않고 버리면 안 된다');
  assert.equal(log[1].aborted, true, '진 쪽 요청은 끊는다');
});

test('먼저 온 답을 쓴다: 두 번째 모델이 먼저 온 경우', async () => {
  const log = [];
  const started = Date.now();
  const res = await postChat(harness({ env: env(), fetch: scripted({ 'fast-model': null, 'steady-model': 80 }, log) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(await answer(res), 'steady-model 의 답이에요.');
  const took = Date.now() - started;
  assert.ok(took >= 120 && took < 320, `겹치는 시간 50ms + 두 번째 80ms 안팎이어야 한다: ${took}ms`);
  assert.equal(log[0].aborted, true, '진 쪽 요청은 끊는다');
});

test('첫 모델이 바로 오류를 내면 겹치는 시간을 기다리지 않고 두 번째를 부른다', async () => {
  const log = [];
  const started = Date.now();
  const res = await postChat(harness({ env: env({ HIVE_HEDGE_MS: '400' }), fetch: scripted({ 'fast-model': 'error', 'steady-model': 20 }, log) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(await answer(res), 'steady-model 의 답이에요.');
  assert.ok(Date.now() - started < 250, '400ms 를 기다리면 안 된다');
});

test('두 번째 모델이 오류를 내도 첫 모델의 답을 끝까지 기다린다', async () => {
  const log = [];
  const res = await postChat(harness({ env: env(), fetch: scripted({ 'fast-model': 200, 'steady-model': 'error' }, log) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(await answer(res), 'fast-model 의 답이에요.');
});

test('둘 다 답이 없으면 전체 대기 시간 뒤 504, 둘 다 오류면 502', async () => {
  const log = [];
  const started = Date.now();
  const slow = await postChat(harness({ env: env({ HIVE_TIMEOUT_MS: '300' }), fetch: scripted({ 'fast-model': null, 'steady-model': null }, log) }), BODY);
  assert.equal(slow.status, 504);
  const took = Date.now() - started;
  assert.ok(took >= 280 && took < 700, `전체 대기는 300ms 안팎이어야 한다: ${took}ms`);
  assert.equal(log.every((c) => c.aborted), true, '끝나면 남은 요청을 모두 끊는다');
  const broken = await postChat(harness({ env: env(), fetch: scripted({ 'fast-model': 'error', 'steady-model': 'error' }, []) }), BODY);
  assert.equal(broken.status, 502);
});
