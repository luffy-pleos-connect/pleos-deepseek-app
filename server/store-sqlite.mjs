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
CREATE TABLE IF NOT EXISTS event_counts (
  scope       TEXT NOT NULL,
  day         TEXT NOT NULL,
  name        TEXT NOT NULL,
  count       REAL NOT NULL,
  expires_at  INTEGER NOT NULL,
  PRIMARY KEY (scope, day, name)
);
CREATE TABLE IF NOT EXISTS event_devices (
  scope       TEXT NOT NULL,
  day         TEXT NOT NULL,
  device_key  TEXT NOT NULL,
  expires_at  INTEGER NOT NULL,
  PRIMARY KEY (scope, day, device_key)
);
CREATE TABLE IF NOT EXISTS event_recent (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  scope  TEXT NOT NULL,
  t      INTEGER NOT NULL,
  d      TEXT NOT NULL,
  n      TEXT NOT NULL,
  p      TEXT
);
CREATE INDEX IF NOT EXISTS event_recent_scope ON event_recent (scope, id);
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
  const eventCount = db.prepare(`
    INSERT INTO event_counts (scope, day, name, count, expires_at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(scope, day, name) DO UPDATE SET
      count = event_counts.count + excluded.count,
      expires_at = excluded.expires_at
  `);
  const eventDevice = db.prepare(`
    INSERT INTO event_devices (scope, day, device_key, expires_at) VALUES (?, ?, ?, ?)
    ON CONFLICT(scope, day, device_key) DO UPDATE SET expires_at = excluded.expires_at
  `);
  const eventRow = db.prepare('INSERT INTO event_recent (scope, t, d, n, p) VALUES (?, ?, ?, ?, ?)');
  const eventTrim = db.prepare(`
    DELETE FROM event_recent WHERE scope = ? AND id NOT IN (
      SELECT id FROM event_recent WHERE scope = ? ORDER BY id DESC LIMIT 500
    )
  `);
  const eventDayRows = db.prepare(
    'SELECT name, count FROM event_counts WHERE scope = ? AND day = ? AND expires_at > ?',
  );
  const eventDayDevices = db.prepare(
    'SELECT COUNT(*) AS devices FROM event_devices WHERE scope = ? AND day = ? AND expires_at > ?',
  );
  const eventRecent = db.prepare(
    'SELECT t, d, n, p FROM event_recent WHERE scope = ? ORDER BY id DESC LIMIT ?',
  );
  const poolTotals = db.prepare(
    'SELECT total FROM snapshots WHERE device_id <> ? AND is_demo = 0 AND expires_at > ? ORDER BY updated_at DESC LIMIT 500',
  );

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

    async addEvents({ day, scope, deviceKey, counts, rows, ttlSec }) {
      const at = now();
      const expiresAt = at + Math.max(1, Math.floor(ttlSec)) * 1000;
      for (const [name, value] of Object.entries(counts ?? {})) {
        if (!Number.isFinite(value) || value === 0) continue;
        eventCount.run(scope, day, name, value, expiresAt);
      }
      if (typeof deviceKey === 'string' && deviceKey.length > 0) {
        eventDevice.run(scope, day, deviceKey, expiresAt);
      }
      if (Array.isArray(rows) && rows.length > 0) {
        for (const row of rows) {
          eventRow.run(scope, Number(row.t) || at, row.d, row.n, row.p == null ? null : JSON.stringify(row.p));
        }
        eventTrim.run(scope, scope);
      }
    },

    async readEvents({ days, scope, recentLimit }) {
      const at = now();
      const out = {};
      for (const day of days) {
        const counts = {};
        for (const row of eventDayRows.all(scope, day, at)) counts[row.name] = Number(row.count);
        const devices = Number(eventDayDevices.get(scope, day, at)?.devices ?? 0);
        out[day] = { counts, devices };
      }
      const limit = Number.isFinite(recentLimit) && recentLimit > 0 ? Math.floor(recentLimit) : 200;
      const recent = eventRecent.all(scope, limit).map((row) => {
        let p;
        if (row.p != null) {
          try {
            p = JSON.parse(row.p);
          } catch {
            p = undefined;
          }
        }
        return { t: Number(row.t), d: row.d, n: row.n, ...(p === undefined ? {} : { p }) };
      });
      return { days: out, recent };
    },

    async realTotals(excludeDeviceId) {
      return poolTotals.all(excludeDeviceId ?? '', now()).map((row) => Number(row.total));
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
      db.exec('DELETE FROM snapshots; DELETE FROM rate_counters; DELETE FROM event_counts; DELETE FROM event_devices; DELETE FROM event_recent;');
    },

    close() {
      db.close();
    },
  };
}

export default createSqliteStore;
