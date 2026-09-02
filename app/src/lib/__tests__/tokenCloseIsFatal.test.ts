import { kindFromCloseCode, toRemotlyError } from '../errors';

// The pairing dial loop tries the next target only while a failure classifies
// as 'network'. A pairing token is single-use, so once the daemon has refused
// it there is nothing another address can do, and continuing produced a run of
// token_used failures that surfaced as a generic pairing failure.
describe('token close code stops the pairing dial loop', () => {
  const shouldTryNextTarget = (closeCode: number): boolean =>
    toRemotlyError(
      { kind: kindFromCloseCode(closeCode), code: closeCode },
      'network',
    ).kind === 'network';

  it('does not try another address after a token failure', () => {
    // 4003 is token_unknown, token_expired, and token_used alike.
    expect(shouldTryNextTarget(4003)).toBe(false);
  });

  it('does not try another address after an auth failure', () => {
    expect(shouldTryNextTarget(4001)).toBe(false);
  });

  it('still tries another address on an ordinary connect failure', () => {
    // A plain Error carries no kind, so it takes the loop's network fallback.
    expect(toRemotlyError(new Error('connect refused'), 'network').kind).toBe(
      'network',
    );
  });
});
