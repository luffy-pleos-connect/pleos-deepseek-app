import test from 'node:test';
import assert from 'node:assert/strict';
import { createFetch } from './vercel-entry.mjs';
import { createMemoryStore } from './store-memory.mjs';

const quiet = { quiet: true };

test('/api 를 떼고 core 로 넘긴다: GET /api/opponent 는 시드 상대를 돌려준다', async () => {
  const fetch = createFetch({ env: quiet, store: createMemoryStore() });
  const res = await fetch(new Request('https://example.invalid/api/opponent?deviceId=device-aaaa-0001'));
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(typeof body.nickname, 'string');
  assert.equal(body.isSeed, true);
});

test('본문이 있는 POST 도 넘어간다: 키가 없으면 /api/chat 은 502', async () => {
  const fetch = createFetch({ env: quiet, store: createMemoryStore() });
  const res = await fetch(
    new Request('https://example.invalid/api/chat', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-device-id': 'device-aaaa-0001' },
      body: JSON.stringify({ messages: [{ role: 'user', content: '경고등 뭐야' }], tools: [] }),
    }),
  );
  assert.equal(res.status, 502);
});

test('잘못된 본문은 core 의 검증을 그대로 받는다: 400', async () => {
  const fetch = createFetch({ env: quiet, store: createMemoryStore() });
  const res = await fetch(
    new Request('https://example.invalid/api/chat', { method: 'POST', body: '{"messages":"x"}' }),
  );
  assert.equal(res.status, 400);
});

test('저장소 환경 변수가 없으면 500 not_configured 로 답하고 죽지 않는다', async () => {
  const fetch = createFetch({ env: {} });
  const res = await fetch(new Request('https://example.invalid/api/opponent?deviceId=device-aaaa-0001'));
  assert.equal(res.status, 500);
  assert.deepEqual(await res.json(), { error: 'not_configured' });
});

test('api 의 세 파일은 fetch 를 내보낸다', async () => {
  for (const name of ['chat', 'opponent', 'snapshots']) {
    const mod = await import(`./api/${name}.mjs`);
    assert.equal(typeof mod.default.fetch, 'function');
  }
});
