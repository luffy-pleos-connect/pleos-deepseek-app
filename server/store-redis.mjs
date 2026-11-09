import { createHash, timingSafeEqual } from 'node:crypto';

const SNAP = 'moca:snap:';
const POOL = 'moca:pool';

function hashEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

const keyOf = (deviceId) => createHash('sha256').update(`device:${deviceId}`, 'utf8').digest('hex').slice(0, 40);

export function createRedisStore({
  url = process.env.UPSTASH_REDIS_REST_URL,
  token = process.env.UPSTASH_REDIS_REST_TOKEN,
  now = Date.now,
  fetchImpl = globalThis.fetch,
  timeoutMs = 5000,
} = {}) {
  if (typeof url !== 'string' || url.length === 0) throw new Error('UPSTASH_REDIS_REST_URL is required');
  if (typeof token !== 'string' || token.length === 0) throw new Error('UPSTASH_REDIS_REST_TOKEN is required');
  const base = url.replace(/\/+$/, '');

  async function pipeline(commands) {
    const response = await fetchImpl(`${base}/pipeline`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify(commands),
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) throw new Error(`upstash ${response.status}`);
    const rows = await response.json();
    if (!Array.isArray(rows)) throw new Error('upstash bad response');
    for (const row of rows) {
      if (row && row.error) throw new Error('upstash command failed');
    }
    return rows.map((row) => (row == null ? null : row.result));
  }

  const command = (...args) => pipeline([args]).then((rows) => rows[0]);

  return {
    async putSnapshot(deviceId, snapshot) {
      const at = now();
      const key = SNAP + keyOf(deviceId);
      const raw = await command('GET', key);
      if (raw) {
        let existing;
        try {
          existing = JSON.parse(raw);
        } catch {
          existing = null;
        }
        if (existing && !hashEquals(existing.secretHash, snapshot.secretHash)) {
          return { ok: false, reason: 'secret_mismatch' };
        }
      }
      const value = JSON.stringify({
        secretHash: snapshot.secretHash,
        card: snapshot.card,
        isDemo: snapshot.isDemo === true,
        inspectedAt: snapshot.inspectedAt,
      });
      const ttlSec = Math.max(1, Math.ceil(snapshot.ttlMs / 1000));
      const expiresAt = at + ttlSec * 1000;
      const commands = [['SET', key, value, 'EX', String(ttlSec)]];
      if (snapshot.isDemo === true) commands.push(['ZREM', POOL, key]);
      else commands.push(['ZADD', POOL, String(expiresAt), key]);
      commands.push(['ZREMRANGEBYSCORE', POOL, '-inf', String(at)]);
      await pipeline(commands);
      return { ok: true };
    },

    async getSnapshot(deviceId) {
      const raw = await command('GET', SNAP + keyOf(deviceId));
      if (!raw) return null;
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch {
        return null;
      }
      return {
        deviceId,
        card: parsed.card,
        isDemo: parsed.isDemo === true,
        inspectedAt: parsed.inspectedAt,
        expiresAt: 0,
      };
    },

    async deleteSnapshot(deviceId, secretHash) {
      const key = SNAP + keyOf(deviceId);
      const raw = await command('GET', key);
      if (!raw) {
        await pipeline([['ZREM', POOL, key]]);
        return { ok: true };
      }
      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch {
        parsed = null;
      }
      if (parsed && !hashEquals(parsed.secretHash, secretHash)) {
        return { ok: false, reason: 'secret_mismatch' };
      }
      await pipeline([
        ['DEL', key],
        ['ZREM', POOL, key],
      ]);
      return { ok: true };
    },

    async randomOpponent(deviceId) {
      const at = now();
      const self = SNAP + keyOf(deviceId);
      const members = await pipeline([
        ['ZREMRANGEBYSCORE', POOL, '-inf', String(at)],
        ['ZRANGEBYSCORE', POOL, String(at), '+inf', 'LIMIT', '0', '200'],
      ]);
      const keys = (members[1] ?? []).filter((k) => k !== self);
      if (keys.length === 0) return null;
      for (let attempt = 0; attempt < 3 && keys.length > 0; attempt += 1) {
        const index = Math.floor(Math.random() * keys.length);
        const key = keys[index];
        const raw = await command('GET', key);
        if (!raw) {
          keys.splice(index, 1);
          continue;
        }
        let parsed;
        try {
          parsed = JSON.parse(raw);
        } catch {
          keys.splice(index, 1);
          continue;
        }
        if (parsed.isDemo === true) continue;
        return { deviceId: null, card: parsed.card, isDemo: false };
      }
      return null;
    },

    async hit(key, limit, windowSec) {
      const seconds = Math.max(1, Math.floor(windowSec));
      const rows = await pipeline([
        ['INCR', key],
        ['EXPIRE', key, String(seconds), 'NX'],
        ['PTTL', key],
      ]);
      const count = Number(rows[0]) || 0;
      const pttl = Number(rows[2]);
      const resetAt = pttl > 0 ? now() + pttl : now() + seconds * 1000;
      return { allowed: count <= limit, count, resetAt };
    },
  };
}

export default createRedisStore;
