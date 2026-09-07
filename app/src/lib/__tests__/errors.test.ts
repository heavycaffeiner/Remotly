import { describe, expect, it } from '@jest/globals';

import { makeRemotlyError, toRemotlyError, userFacingMessage } from '../errors';

describe('toRemotlyError', () => {
  it('normalizes a plain error into a displayable state', () => {
    const err = toRemotlyError(new Error('boom'), 'network');
    expect(err.kind).toBe('network');
    expect(userFacingMessage(err)).toBe(
      'Cannot reach the host. Check the network and try again.',
    );
    expect(err.cause).toBeInstanceOf(Error);
  });

  it('preserves a structured RemotlyError', () => {
    const original = makeRemotlyError('storage', -2, 'raw');
    const err = toRemotlyError(original);
    expect(err.kind).toBe('storage');
    expect(err.code).toBe(-2);
    expect(userFacingMessage(err)).toBe(
      'Saved hosts could not be read. Your data has not been changed.',
    );
  });

  it('falls back to the unknown message for unrecognizable values', () => {
    const err = toRemotlyError(42);
    expect(err.kind).toBe('unknown');
    expect(userFacingMessage(err)).toBe('Something went wrong. Try again.');
  });
});
