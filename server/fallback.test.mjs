// 계획 v10 4장: 음성 모델이 느릴 때 두 번째 모델로 한 번 더. lead 가 쓴 테스트다. 워커는 고치지 않는다.
// 배경: 2026-11-12 올린 서버로 GLM 5.3 Flash 를 30번 불러 6번이 8초를 넘겼다(504). 나머지는 중앙 2.7초.
import test from 'node:test';
import assert from 'node:assert/strict';
import { harness, postChat } from './test-helpers.mjs';

const HIVE_URL = 'https://example.invalid/chat';
const BODY = { messages: [{ role: 'user', content: '경고등 뭐야' }] };
const ok = (text) => new Response(JSON.stringify({ choices: [{ message: { content: text } }] }), { status: 200 });

// 첫 모델은 abort 될 때까지 답하지 않고, 두 번째 모델은 바로 답한다.
function slowThenFast(calls) {
  return (url, init) => {
    const model = JSON.parse(init.body).model;
    calls.push(model);
    if (model === 'fast-model') {
      return new Promise((_, reject) => {
        init.signal.addEventListener('abort', () => reject(new Error('aborted')));
      });
    }
    return Promise.resolve(ok('두 번째 모델의 답이에요.'));
  };
}

const env = (extra = {}) => ({
  HIVE_API_KEY: 'k', HIVE_API_URL: HIVE_URL, HIVE_MODEL: 'fast-model', HIVE_FALLBACK_MODEL: 'steady-model',
  HIVE_FIRST_TIMEOUT_MS: '60', HIVE_TIMEOUT_MS: '400', ...extra,
});

test('첫 모델이 HIVE_FIRST_TIMEOUT_MS 안에 답하지 않으면 두 번째 모델로 한 번 더 부른다', async () => {
  const calls = [];
  const started = Date.now();
  const res = await postChat(harness({ env: env(), fetch: slowThenFast(calls) }), BODY);
  assert.equal(res.status, 200);
  assert.equal(JSON.parse(await res.text()).message.content, '두 번째 모델의 답이에요.');
  assert.deepEqual(calls, ['fast-model', 'steady-model']);
  assert.ok(Date.now() - started < 350, '첫 대기 60ms 뒤 바로 넘어가야 한다');
});

test('첫 모델이 제때 답하면 한 번만 부른다', async () => {
  const calls = [];
  const fetch = (url, init) => {
    calls.push(JSON.parse(init.body).model);
    return Promise.resolve(ok('첫 모델의 답이에요.'));
  };
  const res = await postChat(harness({ env: env(), fetch }), BODY);
  assert.equal(res.status, 200);
  assert.deepEqual(calls, ['fast-model']);
});

test('두 번째 모델도 전체 대기 시간(HIVE_TIMEOUT_MS)을 넘기면 504', async () => {
  const calls = [];
  const never = (url, init) => {
    calls.push(JSON.parse(init.body).model);
    return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  };
  const started = Date.now();
  const res = await postChat(harness({ env: env(), fetch: never }), BODY);
  assert.equal(res.status, 504);
  assert.deepEqual(calls, ['fast-model', 'steady-model']);
  const took = Date.now() - started;
  assert.ok(took >= 380 && took < 900, `전체 대기는 400ms 안팎이어야 한다: ${took}ms`);
});

test('HIVE_FALLBACK_MODEL 이 없으면 전처럼 한 번만 부르고 전체 대기 시간을 쓴다', async () => {
  const calls = [];
  const never = (url, init) => {
    calls.push(JSON.parse(init.body).model);
    return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  };
  const res = await postChat(harness({ env: env({ HIVE_FALLBACK_MODEL: '' }), fetch: never }), BODY);
  assert.equal(res.status, 504);
  assert.deepEqual(calls, ['fast-model']);
});

test('첫 모델이 오류(대기 초과가 아님)를 돌려주면 두 번째 모델로 넘어간다', async () => {
  const calls = [];
  const fetch = (url, init) => {
    const model = JSON.parse(init.body).model;
    calls.push(model);
    return Promise.resolve(model === 'fast-model' ? new Response('rate limited', { status: 429 }) : ok('두 번째 모델의 답이에요.'));
  };
  const res = await postChat(harness({ env: env(), fetch }), BODY);
  assert.equal(res.status, 200);
  assert.deepEqual(calls, ['fast-model', 'steady-model']);
});
