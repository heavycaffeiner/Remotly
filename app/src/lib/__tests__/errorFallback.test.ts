import { toRemotlyError } from '../errors';

// A plain Error must take the caller's fallback kind.
//
// An SSH connect loop gives each host a timeout and rejects with a bare Error
// when it does not answer. The caller continues only while the failure
// classifies as 'network': anything else means the connection itself is wrong
// and retrying would not help. If a timeout landed on 'unknown' instead, the
// caller could not tell a dead host from a broken payload.
describe('toRemotlyError fallback', () => {
  it('classifies a bare Error as the caller-supplied kind', () => {
    const err = toRemotlyError(
      new Error('no answer from this address'),
      'network',
    );
    expect(err.kind).toBe('network');
  });

  it('classifies a plain string as the caller-supplied kind', () => {
    expect(toRemotlyError('boom', 'network').kind).toBe('network');
  });

  it('still honours an explicit kind on the value', () => {
    const err = toRemotlyError({ kind: 'storage' }, 'network');
    expect(err.kind).toBe('storage');
  });

  it('defaults to unknown with no fallback given', () => {
    expect(toRemotlyError(new Error('x')).kind).toBe('unknown');
  });
});
