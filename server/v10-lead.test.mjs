// 과제 39 를 lead 가 읽고 고친 것의 테스트.
import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './core.mjs';
import { DEV, harness, req } from './test-helpers.mjs';

const TOKEN = 'admin-token-for-tests-0001';
const NOW = Date.UTC(2026, 10, 12, 3, 0, 0);
const fixed = () => harness({ now: () => NOW, env: { now: () => NOW, ADMIN_TOKEN: TOKEN } });
const stats = (h, token) =>
  handle(req('GET', '/stats', { headers: { authorization: `Bearer ${token}` } }), h.store, h.env);

test('소수점이 있는 답 시간은 반올림해서 센다(Redis 는 정수만 더한다)', async () => {
  const h = fixed();
  const res = await handle(
    req('POST', '/events', {
      body: { deviceId: DEV, isDemo: false, events: [{ name: 'voice_ask', at: NOW, props: { online: true, ms: 2500.6 } }] },
    }),
    h.store,
    h.env,
  );
  assert.equal(res.status, 200);
  const body = JSON.parse(await (await stats(h, TOKEN)).text());
  assert.equal(body.totals.voice_ms_sum, 2501);
});

test('글자 수는 같고 바이트 수가 다른 틀린 토큰도 401 이다', async () => {
  const h = fixed();
  const wrong = '가'.repeat(TOKEN.length);
  const res = await handle(req('GET', '/stats', { headers: { authorization: `Bearer ${wrong}` } }), h.store, h.env);
  assert.equal(res.status, 401);
});

test('기기 값 없는 순위 조회는 한 시간 2000번까지다', async () => {
  const h = fixed();
  for (let i = 0; i < 2000; i += 1) await handle(req('GET', '/rank?total=75'), h.store, h.env);
  assert.equal((await handle(req('GET', '/rank?total=75'), h.store, h.env)).status, 429);
});

test('대전은 결과로, 화면 이동은 화면으로, 음성은 의도로 나눠 센다', async () => {
  const h = fixed();
  const ev = (name, props) => ({ name, at: NOW, props });
  await handle(
    req('POST', '/events', {
      body: {
        deviceId: DEV,
        isDemo: false,
        events: [
          ev('battle_done', { result: 'win', opponent: 'seed' }),
          ev('battle_done', { result: 'lose', opponent: 'real' }),
          ev('screen_view', { screen: 'voice' }),
          ev('voice_ask', { intent: 'warnings', online: true, ms: 2000 }),
          ev('fix_tap', { kind: 'repair_shop', source: 'home' }),
        ],
      },
    }),
    h.store,
    h.env,
  );
  const { totals } = JSON.parse(await (await stats(h, TOKEN)).text());
  assert.equal(totals['battle_done|win'], 1);
  assert.equal(totals['battle_done|lose'], 1);
  assert.equal(totals['screen_view|voice'], 1);
  assert.equal(totals['voice_ask|warnings'], 1);
  assert.equal(totals['voice_ask|online'], 1);
  assert.equal(totals['fix_tap|repair_shop'], 1);
});
