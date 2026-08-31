import { normalizeStatus } from '../scanner';

// Native events are untrusted at the JS boundary: an unrecognized state or
// code must not leak through as a bare string.
describe('normalizeStatus', () => {
  it('accepts the known lifecycle states', () => {
    expect(normalizeStatus({ state: 'starting' })).toEqual({
      state: 'starting',
    });
    expect(normalizeStatus({ state: 'ready' })).toEqual({ state: 'ready' });
    expect(normalizeStatus({ state: 'stopped' })).toEqual({ state: 'stopped' });
  });

  it('drops the code and message for a non-error state', () => {
    expect(
      normalizeStatus({ state: 'ready', code: 'bind_failed', message: 'x' }),
    ).toEqual({ state: 'ready' });
  });

  it('keeps a known error code', () => {
    expect(
      normalizeStatus({
        state: 'error',
        code: 'no_camera',
        message: 'This device has no back camera.',
      }),
    ).toEqual({
      state: 'error',
      code: 'no_camera',
      message: 'This device has no back camera.',
    });
  });

  it('maps an unknown error code to unknown', () => {
    expect(normalizeStatus({ state: 'error', code: 'exploded' })).toEqual({
      state: 'error',
      code: 'unknown',
    });
  });

  it('treats an unknown state as an error', () => {
    expect(normalizeStatus({ state: 'weird' })).toEqual({
      state: 'error',
      code: 'unknown',
    });
    expect(normalizeStatus({})).toEqual({ state: 'error', code: 'unknown' });
  });

  it('bounds the message length', () => {
    const long = 'x'.repeat(1000);
    const out = normalizeStatus({
      state: 'error',
      code: 'bind_failed',
      message: long,
    });
    expect(out.message).toHaveLength(200);
  });

  it('omits an empty message', () => {
    expect(
      normalizeStatus({ state: 'error', code: 'bind_failed', message: '' }),
    ).toEqual({ state: 'error', code: 'bind_failed' });
  });

  it('ignores a non-string message', () => {
    const out = normalizeStatus({
      state: 'error',
      code: 'bind_failed',
      message: 42 as unknown as string,
    });
    expect(out.message).toBeUndefined();
  });
});
