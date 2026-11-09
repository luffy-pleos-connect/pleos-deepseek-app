import { handleStandard } from '../server/core.mjs';
import { createRedisStore } from '../server/store-redis.mjs';

let store;

function getStore() {
  if (!store) {
    store = createRedisStore({
      url: process.env.UPSTASH_REDIS_REST_URL,
      token: process.env.UPSTASH_REDIS_REST_TOKEN,
    });
  }
  return store;
}

const env = () => ({
  HIVE_API_URL: process.env.HIVE_API_URL,
  HIVE_API_KEY: process.env.HIVE_API_KEY,
  HIVE_MODEL: process.env.HIVE_MODEL,
  DAILY_CHAT_LIMIT: process.env.DAILY_CHAT_LIMIT,
  HIVE_TIMEOUT_MS: process.env.HIVE_TIMEOUT_MS,
});

export default async function handler(request) {
  return handleStandard(request, getStore(), env());
}

export const config = { runtime: 'nodejs' };
