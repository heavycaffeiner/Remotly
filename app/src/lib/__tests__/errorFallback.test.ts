import { toRemotlyError } from '../errors';

// A plain Error must take the caller's fallback kind.
//
// The pairing dial gives each target a timeout and rejects with a bare Error
// when one does not answer. The loop then continues to the next target only
// while the failure classifies as 'network': anything else means the payload
// itself is wrong and no other address would do better. If a timeout landed on
// 'unknown' instead, one dead address would abort the whole pairing, which is
// the bug the timeout was added to prevent.
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
    const err = toRemotlyError({ kind: 'auth' }, 'network');
    expect(err.kind).toBe('auth');
  });

  it('defaults to unknown with no fallback given', () => {
    expect(toRemotlyError(new Error('x')).kind).toBe('unknown');
  });
});
