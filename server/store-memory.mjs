import { timingSafeEqual } from 'node:crypto';

function hashEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

const RECENT_LIMIT = 500;
const POOL_LIMIT = 500;

export function createMemoryStore({ now = Date.now } = {}) {
  const snapshots = new Map();
  const counters = new Map();
  const events = new Map();
  const recent = new Map();

  const live = (entry) => entry !== undefined && entry.expiresAt > now();

  return {
    async putSnapshot(deviceId, snapshot) {
      const existing = snapshots.get(deviceId);
      if (live(existing) && !hashEquals(existing.secretHash, snapshot.secretHash)) {
        return { ok: false, reason: 'secret_mismatch' };
      }
      snapshots.set(deviceId, {
        deviceId,
        secretHash: snapshot.secretHash,
        card: {
          nickname: snapshot.card.nickname,
          grade: snapshot.card.grade,
          total: snapshot.card.total,
          categories: { ...snapshot.card.categories },
        },
        isDemo: snapshot.isDemo === true,
        inspectedAt: snapshot.inspectedAt,
        expiresAt: now() + snapshot.ttlMs,
      });
      return { ok: true };
    },

    async getSnapshot(deviceId) {
      const entry = snapshots.get(deviceId);
      if (!live(entry)) {
        snapshots.delete(deviceId);
        return null;
      }
      return {
        deviceId: entry.deviceId,
        card: { ...entry.card, categories: { ...entry.card.categories } },
        isDemo: entry.isDemo,
        inspectedAt: entry.inspectedAt,
        expiresAt: entry.expiresAt,
      };
    },

    async deleteSnapshot(deviceId, secretHash) {
      const entry = snapshots.get(deviceId);
      if (!live(entry)) {
        snapshots.delete(deviceId);
        return { ok: true };
      }
      if (!hashEquals(entry.secretHash, secretHash)) return { ok: false, reason: 'secret_mismatch' };
      snapshots.delete(deviceId);
      return { ok: true };
    },

    async randomOpponent(deviceId) {
      const candidates = [];
      for (const entry of snapshots.values()) {
        if (!live(entry)) continue;
        if (entry.deviceId === deviceId) continue;
        if (entry.isDemo) continue;
        candidates.push(entry);
      }
      if (candidates.length === 0) return null;
      const picked = candidates[Math.floor(Math.random() * candidates.length)];
      return {
        deviceId: picked.deviceId,
        card: { ...picked.card, categories: { ...picked.card.categories } },
        isDemo: false,
      };
    },

    async addEvents({ day, scope, deviceKey, counts, rows, ttlSec }) {
      const at = now();
      const expiresAt = at + Math.max(1, Math.floor(ttlSec)) * 1000;
      const bucketKey = `${scope}:${day}`;
      let bucket = events.get(bucketKey);
      if (!bucket || bucket.expiresAt <= at) {
        bucket = { counts: new Map(), devices: new Set(), expiresAt };
        events.set(bucketKey, bucket);
      }
      for (const [name, value] of Object.entries(counts ?? {})) {
        if (!Number.isFinite(value) || value === 0) continue;
        bucket.counts.set(name, (bucket.counts.get(name) ?? 0) + value);
      }
      if (typeof deviceKey === 'string' && deviceKey.length > 0) bucket.devices.add(deviceKey);
      if (Array.isArray(rows) && rows.length > 0) {
        const list = recent.get(scope) ?? [];
        for (const row of rows) list.unshift(row);
        if (list.length > RECENT_LIMIT) list.length = RECENT_LIMIT;
        recent.set(scope, list);
      }
    },

    async readEvents({ days, scope, recentLimit }) {
      const at = now();
      const out = {};
      for (const day of days) {
        const bucket = events.get(`${scope}:${day}`);
        if (!bucket || bucket.expiresAt <= at) {
          out[day] = { counts: {}, devices: 0 };
          continue;
        }
        out[day] = {
          counts: Object.fromEntries(bucket.counts),
          devices: bucket.devices.size,
        };
      }
      const list = recent.get(scope) ?? [];
      const limit = Number.isFinite(recentLimit) && recentLimit > 0 ? Math.floor(recentLimit) : list.length;
      return { days: out, recent: list.slice(0, limit) };
    },

    async realTotals(excludeDeviceId) {
      const totals = [];
      for (const entry of snapshots.values()) {
        if (!live(entry)) continue;
        if (entry.isDemo) continue;
        if (excludeDeviceId != null && entry.deviceId === excludeDeviceId) continue;
        totals.push(entry.card.total);
        if (totals.length >= POOL_LIMIT) break;
      }
      return totals;
    },

    async hit(key, limit, windowSec) {
      const at = now();
      const step = Math.max(1, Math.floor(windowSec)) * 1000;
      const current = counters.get(key);
      if (!current || current.expiresAt <= at) {
        counters.set(key, { count: 1, expiresAt: at + step });
        return { allowed: limit > 0, count: 1, resetAt: at + step };
      }
      current.count += 1;
      return { allowed: current.count <= limit, count: current.count, resetAt: current.expiresAt };
    },

    async reset() {
      snapshots.clear();
      counters.clear();
      events.clear();
      recent.clear();
    },
  };
}

export default createMemoryStore;
