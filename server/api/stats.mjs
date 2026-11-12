// Vercel 함수. 경로 /api/<이 파일 이름> 으로 불린다.
import { createFetch } from '../vercel-entry.mjs';

export const config = { maxDuration: 30 };

export default { fetch: createFetch() };
