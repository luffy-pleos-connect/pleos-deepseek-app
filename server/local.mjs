import { createServer } from 'node:http';
import { handle, MAX_BODY_BYTES } from './core.mjs';
import { createSqliteStore } from './store-sqlite.mjs';

const PORT = Number(process.env.PORT ?? 8787);
const HOST = process.env.HOST ?? '127.0.0.1';

const store = createSqliteStore({ filename: process.env.MOCA_DB ?? 'moca.sqlite' });

const env = {
  HIVE_API_URL: process.env.HIVE_API_URL,
  HIVE_API_KEY: process.env.HIVE_API_KEY,
  HIVE_MODEL: process.env.HIVE_MODEL,
  DAILY_CHAT_LIMIT: process.env.DAILY_CHAT_LIMIT,
  HIVE_TIMEOUT_MS: process.env.HIVE_TIMEOUT_MS,
};

function headersToObject(rawHeaders) {
  const headers = {};
  for (let i = 0; i < rawHeaders.length; i += 2) {
    const name = rawHeaders[i].toLowerCase();
    const value = rawHeaders[i + 1];
    headers[name] = name in headers ? `${headers[name]}, ${value}` : value;
  }
  return headers;
}

function rejectTooLarge(req, res) {
  if (!res.headersSent) {
    res.writeHead(413, { 'content-type': 'application/json; charset=utf-8', connection: 'close' });
    res.end(JSON.stringify({ error: 'too_large', field: 'body' }));
  }
  const path = (req.url ?? '/').split('?')[0];
  console.log(`[moca] 413 0ms ${req.method ?? 'GET'} ${path}`);
  req.destroy();
}

const server = createServer((req, res) => {
  const declared = Number(req.headers['content-length']);
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) {
    rejectTooLarge(req, res);
    return;
  }

  const chunks = [];
  let size = 0;
  let rejected = false;

  req.on('data', (chunk) => {
    if (rejected) return;
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      rejected = true;
      rejectTooLarge(req, res);
      return;
    }
    chunks.push(chunk);
  });

  req.on('end', async () => {
    if (rejected) return;
    const url = `http://${req.headers.host ?? `${HOST}:${PORT}`}${req.url ?? '/'}`;
    let response;
    try {
      response = await handle(
        {
          method: req.method ?? 'GET',
          url,
          headers: headersToObject(req.rawHeaders),
          body: Buffer.concat(chunks),
        },
        store,
        env,
      );
    } catch {
      response = new Response(JSON.stringify({ error: 'internal' }), {
        status: 500,
        headers: { 'content-type': 'application/json; charset=utf-8' },
      });
    }
    if (res.headersSent) return;
    const payload = Buffer.from(await response.arrayBuffer());
    const out = {};
    response.headers.forEach((value, name) => {
      out[name] = value;
    });
    res.writeHead(response.status, out);
    res.end(payload.byteLength > 0 ? payload : undefined);
  });

  req.on('error', () => {
    if (!res.headersSent) {
      res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'bad_request' }));
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[moca] listening on http://${HOST}:${PORT}`);
});

function shutdown() {
  server.close(() => {
    try {
      store.close();
    } catch {
      // 이미 닫힌 저장소는 무시한다
    }
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
