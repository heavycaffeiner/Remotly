import { describe, expect, it } from '@jest/globals';

import {
  kindFromCloseCode,
  makeRemotlyError,
  toRemotlyError,
  userFacingMessage,
} from '../errors';

describe('kindFromCloseCode', () => {
  it('maps the Remotly close range to error kinds', () => {
    expect(kindFromCloseCode(4000)).toBe('protocol');
    expect(kindFromCloseCode(4001)).toBe('auth');
    expect(kindFromCloseCode(4002)).toBe('handshake');
    expect(kindFromCloseCode(4003)).toBe('network');
    expect(kindFromCloseCode(4004)).toBe('protocol');
    expect(kindFromCloseCode(1011)).toBe('unknown');
  });
});

describe('toRemotlyError', () => {
  it('normalizes a plain error into a displayable state', () => {
    const err = toRemotlyError(new Error('boom'), 'network');
    expect(err.kind).toBe('network');
    expect(userFacingMessage(err)).toBe(
      'Cannot reach the device. Check the network and try again.',
    );
    expect(err.cause).toBeInstanceOf(Error);
  });

  it('preserves a structured RemotlyError', () => {
    const original = makeRemotlyError('auth', 4001, 'raw');
    const err = toRemotlyError(original);
    expect(err.kind).toBe('auth');
    expect(err.code).toBe(4001);
    expect(userFacingMessage(err)).toBe(
      'Pairing was rejected. Check the pairing code and try again.',
    );
  });

  it('falls back to the unknown message for unrecognizable values', () => {
    const err = toRemotlyError(42);
    expect(err.kind).toBe('unknown');
    expect(userFacingMessage(err)).toBe('Something went wrong. Try again.');
  });
});
