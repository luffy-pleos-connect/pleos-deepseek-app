import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createSqliteStore } from './store-sqlite.mjs';
import { createRedisStore } from './store-redis.mjs';
import { createMemoryStore } from './store-memory.mjs';

const hash = (value) => createHash('sha256').update(value, 'utf8').digest('hex');
const SECRET_A = hash('secret-a');
const SECRET_B = hash('secret-b');
const TTL = 90 * 86400 * 1000;

const card = (nickname, total = 85.6) => ({
  nickname,
  grade: 'A',
  total,
  categories: { power: 100, brake: 100, tire: 80, electric: 90, safety: 100 },
});

function runStoreContract(label, makeStore) {
  test(`${label}: putSnapshot → getSnapshot 왕복`, async () => {
    const store = makeStore();
    const result = await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    assert.equal(result.ok, true);
    const stored = await store.getSnapshot('dev-1');
    assert.equal(stored.card.nickname, '첫차주');
    assert.equal(stored.isDemo, false);
    assert.deepEqual(stored.card.categories, card('x').categories);
  });

  test(`${label}: 같은 비밀값 upsert 갱신`, async () => {
    const store = makeStore();
    await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    const again = await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('바뀐이름', 90), isDemo: false, inspectedAt: 2000, ttlMs: TTL,
    });
    assert.equal(again.ok, true);
    assert.equal((await store.getSnapshot('dev-1')).card.nickname, '바뀐이름');
  });

  test(`${label}: 비밀값 불일치 upsert → secret_mismatch, 값 유지`, async () => {
    const store = makeStore();
    await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    const bad = await store.putSnapshot('dev-1', {
      secretHash: SECRET_B, card: card('탈취시도'), isDemo: false, inspectedAt: 2000, ttlMs: TTL,
    });
    assert.deepEqual(bad, { ok: false, reason: 'secret_mismatch' });
    assert.equal((await store.getSnapshot('dev-1')).card.nickname, '첫차주');
  });

  test(`${label}: 비밀값 불일치 delete → secret_mismatch, 값 유지`, async () => {
    const store = makeStore();
    await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    const bad = await store.deleteSnapshot('dev-1', SECRET_B);
    assert.deepEqual(bad, { ok: false, reason: 'secret_mismatch' });
    assert.notEqual(await store.getSnapshot('dev-1'), null);
  });

  test(`${label}: 올바른 비밀값 delete → 삭제`, async () => {
    const store = makeStore();
    await store.putSnapshot('dev-1', {
      secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    const good = await store.deleteSnapshot('dev-1', SECRET_A);
    assert.equal(good.ok, true);
    assert.equal(await store.getSnapshot('dev-1'), null);
  });

  test(`${label}: randomOpponent 는 나·데모를 제외`, async () => {
    const store = makeStore();
    await store.putSnapshot('me', {
      secretHash: SECRET_A, card: card('나'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    await store.putSnapshot('demo', {
      secretHash: SECRET_A, card: card('데모'), isDemo: true, inspectedAt: 1000, ttlMs: TTL,
    });
    assert.equal(await store.randomOpponent('me'), null);
    await store.putSnapshot('other', {
      secretHash: SECRET_A, card: card('이웃'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
    });
    const picked = await store.randomOpponent('me');
    assert.equal(picked.card.nickname, '이웃');
    assert.equal(picked.isDemo, false);
  });

  test(`${label}: hit 는 한도를 넘으면 allowed=false`, async () => {
    const store = makeStore();
    for (let i = 0; i < 3; i += 1) {
      const hit = await store.hit('k', 3, 60);
      assert.equal(hit.allowed, true, `${i + 1}회`);
    }
    const blocked = await store.hit('k', 3, 60);
    assert.equal(blocked.allowed, false);
    assert.ok(blocked.resetAt > 0);
  });
}

runStoreContract('memory', () => createMemoryStore());
runStoreContract('sqlite', () => createSqliteStore({ filename: ':memory:' }));

function startClock(start = 1_800_000_000_000) {
  let clock = start;
  return { now: () => clock, advance: (ms) => { clock += ms; } };
}

test('memory: TTL 이 지나면 getSnapshot·randomOpponent 에서 사라진다', async () => {
  const timer = startClock();
  const store = createMemoryStore({ now: timer.now });
  await store.putSnapshot('dev-1', {
    secretHash: SECRET_A, card: card('오래된차'), isDemo: false, inspectedAt: 1000, ttlMs: 1000,
  });
  assert.notEqual(await store.getSnapshot('dev-1'), null);
  timer.advance(1001);
  assert.equal(await store.getSnapshot('dev-1'), null);
  assert.equal(await store.randomOpponent('me'), null);
});

test('sqlite: TTL 이 지나면 getSnapshot·randomOpponent 에서 사라진다', async () => {
  const timer = startClock();
  const store = createSqliteStore({ filename: ':memory:', now: timer.now });
  await store.putSnapshot('dev-1', {
    secretHash: SECRET_A, card: card('오래된차'), isDemo: false, inspectedAt: 1000, ttlMs: 1000,
  });
  timer.advance(1001);
  assert.equal(await store.getSnapshot('dev-1'), null);
  assert.equal(await store.randomOpponent('me'), null);
  store.close();
});

test('memory: hit 창이 지나면 다시 센다', async () => {
  const timer = startClock();
  const store = createMemoryStore({ now: timer.now });
  for (let i = 0; i < 2; i += 1) await store.hit('k', 2, 1);
  assert.equal((await store.hit('k', 2, 1)).allowed, false);
  timer.advance(1001);
  assert.equal((await store.hit('k', 2, 1)).allowed, true);
});

function fakeUpstash() {
  const data = new Map();
  const zset = new Map();
  const calls = [];
  const fetchImpl = async (url, init) => {
    const commands = JSON.parse(init.body);
    calls.push({ url, commands, headers: init.headers });
    const results = commands.map((command) => {
      const [name, ...args] = command.map(String);
      switch (name.toUpperCase()) {
        case 'GET':
          return data.has(args[0]) ? data.get(args[0]) : null;
        case 'SET': {
          data.set(args[0], args[1]);
          return 'OK';
        }
        case 'DEL':
          return data.delete(args[0]) ? 1 : 0;
        case 'INCR': {
          const next = Number(data.get(args[0]) ?? 0) + 1;
          data.set(args[0], String(next));
          return next;
        }
        case 'EXPIRE':
          return data.has(args[0]) ? 1 : 0;
        case 'PTTL':
          return 60000;
        case 'ZADD': {
          const set = zset.get(args[0]) ?? new Map();
          set.set(args[2], Number(args[1]));
          zset.set(args[0], set);
          return 1;
        }
        case 'ZREM': {
          const set = zset.get(args[0]);
          return set && set.delete(args[1]) ? 1 : 0;
        }
        case 'ZREMRANGEBYSCORE': {
          const set = zset.get(args[0]) ?? new Map();
          let removed = 0;
          for (const [member, score] of [...set]) {
            if (score <= Number(args[1])) {
              set.delete(member);
              removed += 1;
            }
          }
          return removed;
        }
        case 'ZRANGEBYSCORE': {
          const set = zset.get(args[0]) ?? new Map();
          const min = Number(args[1]);
          const limit = Number(args[3] ?? 0);
          const members = [...set.entries()].filter(([, score]) => score >= min).map(([member]) => member);
          return limit > 0 ? members.slice(0, limit) : members;
        }
        default:
          return { error: 'unsupported' };
      }
    });
    return new Response(JSON.stringify(results.map((result) => ({ result }))), { status: 200 });
  };
  return { fetchImpl, calls, data };
}

runStoreContract('redis(fake upstash)', () => {
  const { fetchImpl } = fakeUpstash();
  return createRedisStore({ url: 'https://fake.upstash.io', token: 'fake-token', fetchImpl });
});

test('redis: Upstash REST 형식(파이프라인 배열)으로 호출하고 토큰을 헤더로 보낸다', async () => {
  const fake = fakeUpstash();
  const store = createRedisStore({
    url: 'https://fake.upstash.io/', token: 'fake-token', fetchImpl: fake.fetchImpl,
  });
  await store.putSnapshot('dev-1', {
    secretHash: SECRET_A, card: card('첫차주'), isDemo: false, inspectedAt: 1000, ttlMs: TTL,
  });
  assert.ok(fake.calls.length > 0);
  assert.equal(fake.calls[0].url, 'https://fake.upstash.io/pipeline');
  assert.equal(fake.calls[0].headers.authorization, 'Bearer fake-token');
  const write = fake.calls.find((call) => call.commands[0][0] === 'SET');
  assert.ok(write, 'SET 명령이 전송되어야 한다');
  const [setCommand] = write.commands;
  assert.equal(setCommand[0], 'SET');
  assert.equal(setCommand[3], 'EX');
  assert.equal(setCommand[4], String(90 * 86400));
  const stored = JSON.parse(fake.data.get(setCommand[1]));
  assert.equal(stored.card.nickname, '첫차주');
});

test('redis: 설정이 없으면 생성 시 실패', () => {
  assert.throws(() => createRedisStore({ url: '', token: '' }), /UPSTASH_REDIS_REST_URL/);
});
