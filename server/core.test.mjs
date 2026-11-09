import test from 'node:test';
import assert from 'node:assert/strict';
import { handle, handleStandard, SEEDS } from './core.mjs';
const HIVE_URL = 'https://example.invalid/chat';
import { createMemoryStore } from './store-memory.mjs';
import {
  DEV, OTHER, SECRET, deleteSnap, getOpponent, harness, okHive, postChat, postSnap, req, snapshot,
} from './test-helpers.mjs';

test('POST /snapshots 첫 저장 → 200 {ok}', async () => {
  const h = harness();
  const res = await postSnap(h, snapshot());
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });
  const stored = await h.store.getSnapshot(DEV);
  assert.equal(stored.card.nickname, '테스트드라이버');
  assert.equal(stored.isDemo, false);
});

test('POST /snapshots 같은 기기·같은 비밀값 upsert', async () => {
  const h = harness();
  await postSnap(h, snapshot());
  const res = await postSnap(h, snapshot({ nickname: '두번째이름', grade: 'S', total: 95 }));
  assert.equal(res.status, 200);
  const stored = await h.store.getSnapshot(DEV);
  assert.equal(stored.card.nickname, '두번째이름');
  assert.equal(stored.card.grade, 'S');
  assert.equal(stored.card.total, 95);
});

test('POST /snapshots 비밀값 불일치 upsert → 403', async () => {
  const h = harness();
  await postSnap(h, snapshot());
  const res = await postSnap(h, snapshot({ nickname: '바꿔치기' }), 'wrong-secret-9999');
  assert.equal(res.status, 403);
  assert.deepEqual(await res.json(), { error: 'secret_mismatch' });
  const stored = await h.store.getSnapshot(DEV);
  assert.equal(stored.card.nickname, '테스트드라이버');
});

test('DELETE /snapshots 비밀값 불일치 → 403, 데이터 유지', async () => {
  const h = harness();
  await postSnap(h, snapshot());
  const res = await deleteSnap(h, DEV, 'wrong-secret-9999');
  assert.equal(res.status, 403);
  assert.notEqual(await h.store.getSnapshot(DEV), null);
});

test('DELETE /snapshots 올바른 비밀값 → 204, 이후 조회 불가', async () => {
  const h = harness();
  await postSnap(h, snapshot());
  const res = await deleteSnap(h, DEV, SECRET);
  assert.equal(res.status, 204);
  assert.equal(await h.store.getSnapshot(DEV), null);
});

test('POST /snapshots 닉네임 규칙 위반 → 400 nickname', async () => {
  const h = harness();
  for (const nickname of ['a', '이름이너무길어요열한글자', 'bad name!', '']) {
    const res = await postSnap(h, snapshot({ nickname }));
    assert.equal(res.status, 400);
    assert.equal((await res.json()).field, 'nickname');
  }
});

test('POST /snapshots 등급 enum 위반 → 400 grade', async () => {
  const h = harness();
  for (const grade of ['E', 's', 'SS', '']) {
    const res = await postSnap(h, snapshot({ grade }));
    assert.equal(res.status, 400);
    assert.equal((await res.json()).field, 'grade');
  }
});

test('POST /snapshots 점수 범위·카테고리 누락 → 400', async () => {
  const h = harness();
  const tooHigh = await postSnap(h, snapshot({ total: 100.1 }));
  assert.equal(tooHigh.status, 400);
  assert.equal((await tooHigh.json()).field, 'total');

  const negative = await postSnap(h, snapshot({ categories: { power: -1, brake: 100, tire: 100, electric: 100, safety: 100 } }));
  assert.equal(negative.status, 400);
  assert.equal((await negative.json()).field, 'categories');

  const missing = await postSnap(h, snapshot({ categories: { power: 100, brake: 100, tire: 100, electric: 100 } }));
  assert.equal(missing.status, 400);
  assert.equal((await missing.json()).field, 'categories');
});

test('POST /snapshots 잘못된 본문·필수 필드 누락 → 400', async () => {
  const h = harness();
  const badJson = await postSnap(h, '{not json');
  assert.equal(badJson.status, 400);

  const noSecret = await handle(req('POST', '/snapshots', { body: snapshot() }), h.store, h.env);
  assert.equal(noSecret.status, 400);
  assert.equal((await noSecret.json()).field, 'X-Device-Secret');

  const noDevice = await postSnap(h, snapshot({ deviceId: '' }));
  assert.equal(noDevice.status, 400);
  assert.equal((await noDevice.json()).field, 'deviceId');

  const noInspected = await postSnap(h, snapshot({ inspectedAt: 0 }));
  assert.equal(noInspected.status, 400);
  assert.equal((await noInspected.json()).field, 'inspectedAt');
});

test('POST /snapshots 본문 64KB 초과 → 413', async () => {
  const h = harness();
  const res = await handle(
    req('POST', '/snapshots', { body: 'x'.repeat(70000), headers: { 'x-device-secret': SECRET } }),
    h.store,
    h.env,
  );
  assert.equal(res.status, 413);
  assert.deepEqual(await res.json(), { error: 'too_large', field: 'body' });
});

test('POST /snapshots 61번째 → 429 (기기별 60회/시간)', async () => {
  const h = harness();
  for (let i = 0; i < 60; i += 1) {
    const res = await postSnap(h, snapshot({ inspectedAt: Date.UTC(2026, 10, 9, 3, i) }));
    assert.equal(res.status, 200, `${i + 1}번째는 통과해야 한다`);
  }
  const blocked = await postSnap(h, snapshot());
  assert.equal(blocked.status, 429);
});

test('GET /opponent 나 자신은 상대로 나오지 않는다', async () => {
  const h = harness();
  await postSnap(h, snapshot());
  const res = await getOpponent(h, DEV);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.isSeed, true);
  assert.notEqual(body.nickname, '테스트드라이버');
});

test('GET /opponent 데모 스냅샷은 상대 풀에서 제외', async () => {
  const h = harness();
  await postSnap(h, snapshot({ deviceId: OTHER, nickname: '데모상대', isDemo: true }));
  const res = await getOpponent(h, DEV);
  const body = await res.json();
  assert.equal(body.isSeed, true);
  assert.notEqual(body.nickname, '데모상대');
});

test('GET /opponent 다른 기기 실제 스냅샷을 상대로 선택', async () => {
  const h = harness({ env: { random: () => 0 } });
  await postSnap(h, snapshot({ deviceId: OTHER, nickname: '이웃드라이버', grade: 'B', total: 76.5 }));
  const res = await getOpponent(h, DEV);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.nickname, '이웃드라이버');
  assert.equal(body.isSeed, false);
  assert.equal(body.grade, 'B');
  assert.equal(body.total, 76.5);
});

test('GET /opponent 상대가 없으면 시드로 대체', async () => {
  const h = harness({ env: { random: () => 0 } });
  const res = await getOpponent(h, DEV);
  const body = await res.json();
  assert.equal(body.isSeed, true);
  assert.equal(body.nickname, SEEDS[0].nickname);
  assert.equal(body.grade, SEEDS[0].grade);
});

test('GET /opponent 응답에 deviceId·비밀값이 없다', async () => {
  const h = harness({ env: { random: () => 0 } });
  await postSnap(h, snapshot({ deviceId: OTHER, nickname: '이웃드라이버' }));
  const res = await getOpponent(h, DEV);
  const text = await res.text();
  assert.equal(text.includes(OTHER), false);
  assert.equal(text.includes(DEV), false);
  assert.equal(text.includes(SECRET), false);
  const body = JSON.parse(text);
  assert.deepEqual(Object.keys(body).sort(), ['categories', 'grade', 'isSeed', 'nickname', 'total']);
});

test('GET /opponent 61번째 요청 → 429', async () => {
  const h = harness();
  for (let i = 0; i < 60; i += 1) {
    assert.equal((await getOpponent(h, DEV)).status, 200, `${i + 1}번째`);
  }
  assert.equal((await getOpponent(h, DEV)).status, 429);
});

test('GET /opponent deviceId 없음 → 400', async () => {
  const h = harness();
  const res = await handle(req('GET', '/opponent'), h.store, h.env);
  assert.equal(res.status, 400);
  assert.equal((await res.json()).field, 'deviceId');
});

test('알 수 없는 경로 → 404, 허용되지 않은 메서드 → 405', async () => {
  const h = harness();
  assert.equal((await handle(req('GET', '/nope'), h.store, h.env)).status, 404);
  assert.equal((await handle(req('PUT', '/snapshots'), h.store, h.env)).status, 405);
});

test('POST /chat 키가 없으면 upstream 호출 없이 502', async () => {
  const h = harness();
  let called = false;
  h.env.fetch = async () => {
    called = true;
    throw new Error('should not be called');
  };
  const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] });
  assert.equal(res.status, 502);
  assert.equal(called, false);
});

test('POST /chat 정상 → 200 {message}, Hive 요청 파라미터 고정', async () => {
  const captured = [];
  const h = harness({ env: { HIVE_API_KEY: 'hive-key-should-not-leak', HIVE_API_URL: HIVE_URL, HIVE_TIMEOUT_MS: '2000' }, fetch: okHive(captured) });
  const res = await postChat(h, {
    messages: [{ role: 'user', content: '차 상태 알려줘' }],
    tools: [{ type: 'function', function: { name: 'getStatus', parameters: { type: 'object' } } }],
  });
  assert.equal(res.status, 200);
  const text = await res.text();
  assert.deepEqual(JSON.parse(text), {
    message: { role: 'assistant', content: '지금은 괜찮아요', reasoning_content: '', tool_calls: null },
  });
  assert.equal(text.includes('hive-key-should-not-leak'), false);
  assert.equal(captured.length, 1);
  assert.equal(captured[0].url, HIVE_URL);
  assert.equal(captured[0].body.model, 'deepseek-ai/deepseek-v4.1-flash-1017');
  assert.equal(captured[0].body.temperature, 0.3);
  assert.equal(captured[0].body.max_tokens, 300);
  assert.equal(captured[0].body.messages.length, 1);
  assert.equal(captured[0].init.headers.authorization, 'Bearer hive-key-should-not-leak');
});

test('POST /chat 업스트림 실패 → 502', async () => {
  const h = harness({
    env: { HIVE_API_KEY: 'k-1234', HIVE_API_URL: HIVE_URL },
    fetch: async () => {
      throw new Error('network down');
    },
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] });
  assert.equal(res.status, 502);
});

test('POST /chat 업스트림 비정상 응답 → 502', async () => {
  const h = harness({
    env: { HIVE_API_KEY: 'k-1234', HIVE_API_URL: HIVE_URL },
    fetch: async () => new Response('<html>bad gateway</html>', { status: 503 }),
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] });
  assert.equal(res.status, 502);
});

test('POST /chat 20초 타임아웃 → 504', async () => {
  const h = harness({
    env: { HIVE_API_KEY: 'k-1234', HIVE_TIMEOUT_MS: '25' },
    fetch: () => new Promise(() => {}),
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] });
  assert.equal(res.status, 504);
});

test('POST /chat messages 30개 초과 → 400', async () => {
  const h = harness();
  const messages = Array.from({ length: 31 }, () => ({ role: 'user', content: 'hi' }));
  const res = await postChat(h, { messages });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).field, 'messages');
});

test('POST /chat 기기별 31번째 → 429', async () => {
  const h = harness({ env: { HIVE_API_KEY: 'k-1234', HIVE_API_URL: HIVE_URL }, fetch: okHive() });
  const headers = { 'x-device-id': DEV };
  for (let i = 0; i < 30; i += 1) {
    const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] }, headers);
    assert.equal(res.status, 200, `${i + 1}번째`);
  }
  const blocked = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] }, headers);
  assert.equal(blocked.status, 429);
});

test('POST /chat 하루 전체 상한 초과 → 429', async () => {
  const h = harness({ env: { HIVE_API_KEY: 'k-1234', DAILY_CHAT_LIMIT: '2' }, fetch: okHive() });
  const first = await postChat(h, { messages: [{ role: 'user', content: '1' }] });
  const second = await postChat(h, { messages: [{ role: 'user', content: '2' }] });
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  const blocked = await postChat(h, { messages: [{ role: 'user', content: '3' }] });
  assert.equal(blocked.status, 429);
  assert.deepEqual(await blocked.json(), { error: 'daily_limit' });
});

test('로그에 키·deviceId·비밀값이 남지 않는다', async () => {
  const key = 'hive-secret-key-abcdef';
  const h = harness({ env: { HIVE_API_KEY: key }, fetch: okHive() });
  await postSnap(h, snapshot());
  await getOpponent(h, DEV);
  await postChat(h, { messages: [{ role: 'user', content: '안녕' }] }, { 'x-device-id': DEV });
  assert.ok(h.logs.length >= 3);
  const joined = h.logs.join('\n');
  assert.equal(joined.includes(key), false);
  assert.equal(joined.includes(DEV), false);
  assert.equal(joined.includes(SECRET), false);
  for (const line of h.logs) assert.match(line, /^\[moca\] \d{3} \d+ms [A-Z]+ \/[a-z]*$/);
});

test('handleStandard(표준 Request)로도 동작', async () => {
  const h = harness();
  const res = await handleStandard(
    new Request('http://moca.test/snapshots', {
      method: 'POST',
      headers: { 'x-device-secret': SECRET, 'content-type': 'application/json' },
      body: JSON.stringify(snapshot()),
    }),
    h.store,
    h.env,
  );
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true });

  const over = await handleStandard(
    new Request('http://moca.test/snapshots', {
      method: 'POST',
      headers: { 'x-device-secret': SECRET },
      body: JSON.stringify(snapshot({ padding: 'x'.repeat(70000) })),
    }),
    h.store,
    h.env,
  );
  assert.equal(over.status, 413);
});

test('handle(표준 Request)도 직접 받는다', async () => {
  const h = harness();
  const res = await handle(
    new Request(`http://moca.test/opponent?deviceId=${DEV}`),
    h.store,
    h.env,
  );
  assert.equal(res.status, 200);
  assert.equal((await res.json()).isSeed, true);
});

test('스냅샷은 마지막 갱신 후 90일이 지나면 상대 풀에서 사라진다', async () => {
  let clock = Date.UTC(2026, 10, 9, 3, 0, 0);
  const store = createMemoryStore({ now: () => clock });
  const h = harness({ store, now: () => clock, env: { random: () => 0 } });
  await postSnap(h, snapshot({ deviceId: OTHER, nickname: '오래된상대' }));
  clock += 89 * 86400000;
  const before = await getOpponent(h, DEV);
  assert.equal((await before.json()).nickname, '오래된상대');
  clock += 2 * 86400000;
  assert.equal(await store.getSnapshot(OTHER), null);
  const after = await getOpponent(h, DEV);
  assert.equal((await after.json()).isSeed, true);
});

test('POST /chat 도구 호출이 오면 tool_calls 를 보존한다', async () => {
  const toolMessage = {
    role: 'assistant',
    content: '',
    tool_calls: [
      { id: 'call_1', type: 'function', function: { name: 'get_active_warnings', arguments: '{}' } },
    ],
  };
  const h = harness({
    env: { HIVE_API_KEY: 'k', HIVE_API_URL: HIVE_URL, HIVE_TIMEOUT_MS: '2000' },
    fetch: async () => new Response(JSON.stringify({ choices: [{ message: toolMessage }] }), { status: 200 }),
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '경고등 뭐야' }] });
  assert.equal(res.status, 200);
  const body = JSON.parse(await res.text());
  assert.equal(body.message.tool_calls.length, 1);
  assert.equal(body.message.tool_calls[0].function.name, 'get_active_warnings');
  assert.equal(body.message.content, '');
});

test('POST /chat 추론형 응답은 reasoning_content 로 대체한다', async () => {
  const h = harness({
    env: { HIVE_API_KEY: 'k', HIVE_API_URL: HIVE_URL, HIVE_TIMEOUT_MS: '2000' },
    fetch: async () => new Response(
      JSON.stringify({ choices: [{ message: { role: 'assistant', content: '', reasoning_content: '지금은 괜찮아요' } }] }),
      { status: 200 },
    ),
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '상태 알려줘' }] });
  assert.equal(res.status, 200);
  const body = JSON.parse(await res.text());
  assert.equal(body.message.reasoning_content, '지금은 괜찮아요');
  assert.equal(body.message.tool_calls, null);
});

test('POST /chat content·reasoning·tool_calls 가 모두 비면 502', async () => {
  const h = harness({
    env: { HIVE_API_KEY: 'k', HIVE_API_URL: HIVE_URL, HIVE_TIMEOUT_MS: '2000' },
    fetch: async () => new Response(
      JSON.stringify({ choices: [{ message: { role: 'assistant', content: '' } }] }),
      { status: 200 },
    ),
  });
  const res = await postChat(h, { messages: [{ role: 'user', content: '안녕' }] });
  assert.equal(res.status, 502);
});
