import { DatabaseSync } from 'node:sqlite';
import { timingSafeEqual } from 'node:crypto';

function hashEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  return timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS snapshots (
  device_id   TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  nickname    TEXT NOT NULL,
  grade       TEXT NOT NULL,
  total       REAL NOT NULL,
  power       REAL,
  brake       REAL,
  tire        REAL,
  electric    REAL,
  safety      REAL,
  is_demo     INTEGER NOT NULL DEFAULT 0,
  inspected_at INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS snapshots_pool ON snapshots (is_demo, expires_at);
CREATE TABLE IF NOT EXISTS rate_counters (
  bucket      TEXT PRIMARY KEY,
  count       INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);
`;

function toCard(row) {
  return {
    nickname: row.nickname,
    grade: row.grade,
    total: row.total,
    categories: {
      power: row.power,
      brake: row.brake,
      tire: row.tire,
      electric: row.electric,
      safety: row.safety,
    },
  };
}

export function createSqliteStore({ filename = ':memory:', now = Date.now } = {}) {
  const db = new DatabaseSync(filename);
  if (filename !== ':memory:') db.exec('PRAGMA journal_mode = WAL');
  db.exec('PRAGMA busy_timeout = 3000');
  db.exec(SCHEMA);

  const selectOne = db.prepare('SELECT * FROM snapshots WHERE device_id = ?');
  const upsert = db.prepare(`
    INSERT INTO snapshots (device_id, secret_hash, nickname, grade, total, power, brake, tire, electric, safety,
                           is_demo, inspected_at, updated_at, expires_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(device_id) DO UPDATE SET
      secret_hash = excluded.secret_hash,
      nickname = excluded.nickname,
      grade = excluded.grade,
      total = excluded.total,
      power = excluded.power,
      brake = excluded.brake,
      tire = excluded.tire,
      electric = excluded.electric,
      safety = excluded.safety,
      is_demo = excluded.is_demo,
      inspected_at = excluded.inspected_at,
      updated_at = excluded.updated_at,
      expires_at = excluded.expires_at
  `);
  const deleteOne = db.prepare('DELETE FROM snapshots WHERE device_id = ?');
  const sweep = db.prepare('DELETE FROM snapshots WHERE expires_at <= ?');
  const pool = db.prepare(
    'SELECT * FROM snapshots WHERE device_id <> ? AND is_demo = 0 AND expires_at > ? ORDER BY updated_at DESC LIMIT 200',
  );
  const counterHit = db.prepare(`
    INSERT INTO rate_counters (bucket, count, expires_at) VALUES (?, 1, ?)
    ON CONFLICT(bucket) DO UPDATE SET
      count = CASE WHEN rate_counters.expires_at <= ? THEN 1 ELSE rate_counters.count + 1 END,
      expires_at = CASE WHEN rate_counters.expires_at <= ? THEN ? ELSE rate_counters.expires_at END
    RETURNING count, expires_at
  `);
  const counterSweep = db.prepare('DELETE FROM rate_counters WHERE expires_at <= ?');

  return {
    async putSnapshot(deviceId, snapshot) {
      const at = now();
      const existing = selectOne.get(deviceId);
      if (existing && existing.expires_at > at && !hashEquals(existing.secret_hash, snapshot.secretHash)) {
        return { ok: false, reason: 'secret_mismatch' };
      }
      const c = snapshot.card.categories;
      upsert.run(
        deviceId,
        snapshot.secretHash,
        snapshot.card.nickname,
        snapshot.card.grade,
        snapshot.card.total,
        c.power ?? null,
        c.brake ?? null,
        c.tire ?? null,
        c.electric ?? null,
        c.safety ?? null,
        snapshot.isDemo ? 1 : 0,
        snapshot.inspectedAt,
        at,
        at + snapshot.ttlMs,
      );
      return { ok: true };
    },

    async getSnapshot(deviceId) {
      const row = selectOne.get(deviceId);
      if (!row || row.expires_at <= now()) return null;
      return {
        deviceId: row.device_id,
        card: toCard(row),
        isDemo: row.is_demo === 1,
        inspectedAt: row.inspected_at,
        expiresAt: row.expires_at,
      };
    },

    async deleteSnapshot(deviceId, secretHash) {
      const row = selectOne.get(deviceId);
      if (!row || row.expires_at <= now()) {
        deleteOne.run(deviceId);
        return { ok: true };
      }
      if (!hashEquals(row.secret_hash, secretHash)) return { ok: false, reason: 'secret_mismatch' };
      deleteOne.run(deviceId);
      return { ok: true };
    },

    async randomOpponent(deviceId) {
      const at = now();
      const rows = pool.all(deviceId, at);
      if (rows.length === 0) return null;
      const row = rows[Math.floor(Math.random() * rows.length)];
      return { deviceId: row.device_id, card: toCard(row), isDemo: false };
    },

    async hit(key, limit, windowSec) {
      const at = now();
      const step = Math.max(1, Math.floor(windowSec)) * 1000;
      sweep.run(at);
      const row = counterHit.get(key, at + step, at, at, at + step);
      const count = Number(row.count);
      const resetAt = Number(row.expires_at);
      if (count === 1) counterSweep.run(at);
      return { allowed: count <= limit, count, resetAt };
    },

    async reset() {
      db.exec('DELETE FROM snapshots; DELETE FROM rate_counters;');
    },

    close() {
      db.close();
    },
  };
}

export default createSqliteStore;
