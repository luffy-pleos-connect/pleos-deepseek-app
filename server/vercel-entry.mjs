// Vercel 함수가 쓰는 얇은 진입부. api/*.mjs 세 파일이 이것을 부른다.
// 경로의 /api 를 떼고 core.mjs 의 handleStandard 에 그대로 넘긴다.
import { handleStandard } from './core.mjs';
import { createRedisStore } from './store-redis.mjs';

export function createFetch({ env = process.env, store } = {}) {
  let cached = store;
  return async function fetch(request) {
    // 저장소는 첫 요청 때 만든다. 환경 변수가 없으면 여기서 실패하고 500 으로 답한다.
    try {
      // Vercel 의 Upstash 연동은 같은 값을 KV_REST_API_* 이름으로 넣는다. 둘 다 받는다.
      cached ??= createRedisStore({
        url: env.UPSTASH_REDIS_REST_URL || env.KV_REST_API_URL,
        token: env.UPSTASH_REDIS_REST_TOKEN || env.KV_REST_API_TOKEN,
      });
    } catch {
      return new Response(JSON.stringify({ error: 'not_configured' }), {
        status: 500,
        headers: { 'content-type': 'application/json; charset=utf-8' },
      });
    }
    const url = new URL(request.url);
    url.pathname = url.pathname.replace(/^\/api(?=\/|$)/, '') || '/';
    const hasBody = request.method !== 'GET' && request.method !== 'HEAD';
    const forwarded = new Request(url, {
      method: request.method,
      headers: request.headers,
      body: hasBody ? request.body : undefined,
      ...(hasBody ? { duplex: 'half' } : {}),
    });
    return handleStandard(forwarded, cached, {
      HIVE_API_URL: env.HIVE_API_URL,
      HIVE_API_KEY: env.HIVE_API_KEY,
      HIVE_MODEL: env.HIVE_MODEL,
      DAILY_CHAT_LIMIT: env.DAILY_CHAT_LIMIT,
      HIVE_TIMEOUT_MS: env.HIVE_TIMEOUT_MS,
    });
  };
}
