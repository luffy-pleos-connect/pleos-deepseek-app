export const MAX_BODY_BYTES = 65536;

export function headerOf(source, name) {
  const raw = source?.headers;
  if (!raw) return null;
  const wanted = name.toLowerCase();
  if (typeof raw.get === 'function') {
    const value = raw.get(wanted);
    return value == null ? null : String(value);
  }
  if (Array.isArray(raw)) {
    for (const pair of raw) {
      if (Array.isArray(pair) && String(pair[0]).toLowerCase() === wanted) return String(pair[1]);
    }
    return null;
  }
  for (const key of Object.keys(raw)) {
    if (key.toLowerCase() !== wanted) continue;
    const value = raw[key];
    if (Array.isArray(value)) return value.length === 0 ? null : String(value[0]);
    return value == null ? null : String(value);
  }
  return null;
}

export function json(status, body, extraHeaders) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      ...(extraHeaders ?? {}),
    },
  });
}

export function empty(status) {
  return new Response(null, {
    status,
    headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' },
  });
}

function toBytes(body) {
  if (body == null) return new Uint8Array(0);
  if (typeof body === 'string') return new TextEncoder().encode(body);
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (ArrayBuffer.isView(body)) {
    return new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  }
  return null;
}

export async function readJson(source) {
  const declared = Number(headerOf(source, 'content-length'));
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return { tooLarge: true };
  const bytes = toBytes(source?.body);
  if (bytes === null) return { invalid: 'body' };
  if (bytes.byteLength > MAX_BODY_BYTES) return { tooLarge: true };
  if (bytes.byteLength === 0) return { invalid: 'body' };
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return { invalid: 'body' };
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    return { invalid: 'body' };
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { invalid: 'body' };
  }
  return { value };
}
