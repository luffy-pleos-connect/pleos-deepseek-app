export const MAX_MESSAGES = 30;
export const MAX_TOOLS = 16;
export const MAX_CONTENT_CHARS = 8000;

const NICKNAME_RE = /^[가-힣A-Za-z0-9]{2,10}$/;
const GRADES = new Set(['S+', 'S', 'A', 'B', 'C', 'D']);
const CATEGORY_KEYS = ['power', 'brake', 'tire', 'electric', 'safety'];
const ROLES = new Set(['system', 'user', 'assistant', 'tool']);

const isPlainObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const isScore = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;

export function validateDeviceId(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{8,64}$/.test(value);
}

export function validateSecret(value) {
  return typeof value === 'string' && value.length >= 8 && value.length <= 512 && !/\s/.test(value);
}

export function validateSnapshot(input) {
  if (!validateDeviceId(input.deviceId)) return 'deviceId';
  if (typeof input.nickname !== 'string' || !NICKNAME_RE.test(input.nickname)) return 'nickname';
  if (typeof input.grade !== 'string' || !GRADES.has(input.grade)) return 'grade';
  if (!isScore(input.total)) return 'total';
  if (!isPlainObject(input.categories)) return 'categories';
  for (const key of CATEGORY_KEYS) {
    if (!(key in input.categories)) return 'categories';
    const value = input.categories[key];
    if (value !== null && !isScore(value)) return 'categories';
  }
  if (typeof input.isDemo !== 'boolean') return 'isDemo';
  const at = input.inspectedAt;
  if (typeof at !== 'number' || !Number.isFinite(at) || at <= 0) return 'inspectedAt';
  return null;
}

export function validateChat(input) {
  if (!Array.isArray(input.messages) || input.messages.length === 0) return 'messages';
  if (input.messages.length > MAX_MESSAGES) return 'messages';
  for (const message of input.messages) {
    if (!isPlainObject(message)) return 'messages';
    if (typeof message.role !== 'string' || !ROLES.has(message.role)) return 'messages';
    if (typeof message.content !== 'string') return 'messages';
    if (message.content.length > MAX_CONTENT_CHARS) return 'messages';
  }
  if (input.tools !== undefined) {
    if (!Array.isArray(input.tools)) return 'tools';
    if (input.tools.length > MAX_TOOLS) return 'tools';
  }
  return null;
}
