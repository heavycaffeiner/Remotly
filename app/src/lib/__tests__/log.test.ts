import { afterEach, describe, expect, it, jest } from '@jest/globals';

import { log, redact } from '../log';

describe('redact', () => {
  it('masks values of secret-named fields', () => {
    expect(redact({ psk: 'abc123' })).toEqual({ psk: '***redacted***' });
    expect(redact({ api_key: 'k' })).toEqual({ api_key: '***redacted***' });
    expect(redact({ password: 'p', other: 'ok' })).toEqual({
      password: '***redacted***',
      other: 'ok',
    });
  });

  it('masks 64-hex key/session material under any field name', () => {
    const session = 'a'.repeat(64);
    expect(redact({ id: session })).toEqual({ id: '***redacted***' });
    // 63 or 65 hex chars is not key material, so it passes through.
    expect(redact({ short: 'a'.repeat(63) })).toEqual({
      short: 'a'.repeat(63),
    });
    expect(redact({ long: 'a'.repeat(65) })).toEqual({ long: 'a'.repeat(65) });
  });

  it('recurses into nested objects and arrays', () => {
    const input = {
      host: { psk: 'secret', name: 'dev-box' },
      list: [{ token: 't' }, { value: 1 }],
    };
    expect(redact(input)).toEqual({
      host: { psk: '***redacted***', name: 'dev-box' },
      list: [{ token: '***redacted***' }, { value: 1 }],
    });
  });

  it('passes through non-secret primitives', () => {
    expect(redact({ count: 3, flag: true, note: 'hello' })).toEqual({
      count: 3,
      flag: true,
      note: 'hello',
    });
  });
});

describe('log', () => {
  afterEach(() => {
    jest.restoreAllMocks();
    delete (globalThis as Record<string, unknown>).__REMOTLY_DEV__;
  });

  it('emits info/error with redacted fields', () => {
    const info = jest.spyOn(console, 'info').mockImplementation(() => {});
    log.info('connected', { psk: 'secret', host: 'dev-box' });
    expect(info).toHaveBeenCalledTimes(1);
    const arg = info.mock.calls[0][1] as string;
    expect(arg).toContain('"psk":"***redacted***"');
    expect(arg).toContain('"host":"dev-box"');
    expect(arg).not.toContain('secret');
  });

  it('suppresses debug output outside a dev build', () => {
    // Jest runs with __DEV__ true, so the release build is stated explicitly.
    (globalThis as Record<string, unknown>).__REMOTLY_DEV__ = false;
    const debug = jest.spyOn(console, 'debug').mockImplementation(() => {});
    log.debug('trace', { host: 'x' });
    expect(debug).not.toHaveBeenCalled();
  });

  it('emits debug output in a dev build', () => {
    (globalThis as Record<string, unknown>).__REMOTLY_DEV__ = true;
    const debug = jest.spyOn(console, 'debug').mockImplementation(() => {});
    log.debug('trace', { host: 'x' });
    expect(debug).toHaveBeenCalledTimes(1);
  });
});
