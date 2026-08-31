// Development logging for Remotly with secret exclusion.
//
// This module is the only place the app emits logs. Every field is passed
// through `redact` before it reaches the console, so secrets (PSKs, keys,
// tokens, session material) are never written, even in a debug build. This is a
// trust-boundary rule: log output is treated as untrusted-by-default and any
// value that could identify a session or credential is masked.
//
// The module is pure (no Node or native imports) so it runs under Hermes on
// both Android and, later, iOS.

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

// Field names whose values are always masked, regardless of content.
const SECRET_FIELD_RE =
  /(psk|secret|token|passw|api[_-]?key|private[_-]?key|signing[_-]?key|session[_-]?key|credential|authorization|cookie)/i;

// 64 lowercase/uppercase hex chars is the length of a 32-byte key and of a
// Remotly session id. Masking any string of exactly this shape stops raw key or
// session material from leaking even under an innocuous field name.
const HEX_SECRET_RE = /^[0-9a-f]{64}$/i;

const MASKED = '***redacted***';

function isSecretField(name: string): boolean {
  return SECRET_FIELD_RE.test(name);
}

function isSecretValue(value: unknown): boolean {
  return typeof value === 'string' && HEX_SECRET_RE.test(value);
}

// Recursively masks secrets in a value. Objects and arrays are walked; strings
// that look like key material are masked; everything else is passed through.
function redactValue(value: unknown, depth: number): unknown {
  if (depth > 6) return '[truncated]';
  if (isSecretValue(value)) return MASKED;
  if (Array.isArray(value)) return value.map(v => redactValue(v, depth + 1));
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = isSecretField(k) ? MASKED : redactValue(v, depth + 1);
    }
    return out;
  }
  return value;
}

export function redact(
  fields: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(fields)) {
    out[k] = isSecretField(k) ? MASKED : redactValue(v, 0);
  }
  return out;
}

function isDevBuild(): boolean {
  try {
    const g = globalThis as Record<string, unknown>;
    if (typeof g.__REMOTLY_DEV__ === 'boolean')
      return g.__REMOTLY_DEV__ as boolean;
    if (typeof g.__DEV__ === 'boolean') return g.__DEV__ as boolean;
  } catch {
    // Defensive: a broken global must not break logging.
  }
  return false;
}

function emit(
  level: LogLevel,
  message: string,
  fields?: Record<string, unknown>,
): void {
  const safe = fields ? redact(fields) : undefined;
  const line = safe ? `${message} ${JSON.stringify(safe)}` : message;
  // Hermes exposes console; fall back to a no-op if it is absent.
  const c = (globalThis as { console?: Console }).console;
  if (!c) return;
  if (level === 'error') c.error('[remotly]', line);
  else if (level === 'warn') c.warn('[remotly]', line);
  else if (level === 'debug') c.debug('[remotly]', line);
  else c.info('[remotly]', line);
}

export const log = {
  debug(message: string, fields?: Record<string, unknown>): void {
    if (isDevBuild()) emit('debug', message, fields);
  },
  info(message: string, fields?: Record<string, unknown>): void {
    emit('info', message, fields);
  },
  warn(message: string, fields?: Record<string, unknown>): void {
    emit('warn', message, fields);
  },
  error(message: string, fields?: Record<string, unknown>): void {
    emit('error', message, fields);
  },
};
