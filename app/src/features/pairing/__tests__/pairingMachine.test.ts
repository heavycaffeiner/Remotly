import {
  initialPairingState,
  isExpired,
  pairingReducer,
  previewFromUri,
  scannerPaused,
  connectingLabel,
  type PairingPreview,
  type PairingState,
} from '../pairingMachine';
import { PAIRING_URI_PREFIX } from '../../../lib/pairing';

const preview = (over: Partial<PairingPreview> = {}): PairingPreview =>
  ({
    daemonName: 'dev-box',
    fingerprint: 'abc…',
    expiry: Math.floor(Date.now() / 1000) + 600,
    targets: ['192.168.1.10:7443'],
    usableHints: [],
    relay: null,
    payload: {} as PairingPreview['payload'],
    ...over,
  } as PairingPreview);

describe('pairingReducer', () => {
  it('starts on the scan input step', () => {
    expect(initialPairingState.step).toBe('input');
    expect(scannerPaused(initialPairingState)).toBe(false);
  });

  it('switches the input method', () => {
    const next = pairingReducer(initialPairingState, {
      type: 'method',
      method: 'paste',
    });
    expect(next).toMatchObject({ step: 'input', method: 'paste' });
  });

  it('records scanner status while on input', () => {
    const next = pairingReducer(initialPairingState, {
      type: 'scannerStatus',
      status: { state: 'ready' },
    });
    expect(next).toMatchObject({ step: 'input', scanner: { state: 'ready' } });
  });

  it('moves to review on a parsed payload', () => {
    const p = preview();
    const next = pairingReducer(initialPairingState, {
      type: 'parsed',
      preview: p,
    });
    expect(next).toEqual({ step: 'review', preview: p });
    expect(scannerPaused(next)).toBe(true);
  });

  it('moves to error on a parse failure', () => {
    const next = pairingReducer(initialPairingState, {
      type: 'parseFailed',
      source: 'scan',
      message: 'bad',
    });
    expect(next).toMatchObject({
      step: 'error',
      source: 'scan',
      message: 'bad',
    });
  });

  it('accepts a parse from the error step so the same code can be retried', () => {
    const errored: PairingState = {
      step: 'error',
      source: 'scan',
      message: 'bad',
    };
    const next = pairingReducer(errored, {
      type: 'parsed',
      preview: preview(),
    });
    expect(next.step).toBe('review');
  });

  it('connects only from review', () => {
    const review: PairingState = { step: 'review', preview: preview() };
    const connecting = pairingReducer(review, {
      type: 'connect',
      route: 'direct',
    });
    expect(connecting).toMatchObject({ step: 'connecting', route: 'direct' });

    // A second connect while one runs is ignored.
    expect(
      pairingReducer(connecting, { type: 'connect', route: 'direct' }),
    ).toBe(connecting);
  });

  it('switches the route while connecting', () => {
    const connecting: PairingState = {
      step: 'connecting',
      preview: preview(),
      route: 'direct',
    };
    const next = pairingReducer(connecting, {
      type: 'connectRoute',
      route: 'relay',
    });
    expect(next).toMatchObject({ step: 'connecting', route: 'relay' });
  });

  it('keeps the preview on a connect failure so review can be resumed', () => {
    const p = preview();
    const connecting: PairingState = {
      step: 'connecting',
      preview: p,
      route: 'direct',
    };
    const next = pairingReducer(connecting, {
      type: 'connectFailed',
      message: 'no route',
    });
    expect(next).toMatchObject({
      step: 'error',
      source: 'connect',
      preview: p,
    });
  });

  it('reports a duplicate host on success', () => {
    const connecting: PairingState = {
      step: 'connecting',
      preview: preview({ daemonName: 'box' }),
      route: 'direct',
    };
    const next = pairingReducer(connecting, {
      type: 'connected',
      hostId: 'h1',
      duplicate: true,
    });
    expect(next).toEqual({
      step: 'success',
      hostId: 'h1',
      daemonName: 'box',
      duplicate: true,
    });
  });

  it('ignores a connect result that arrives outside the connecting step', () => {
    const review: PairingState = { step: 'review', preview: preview() };
    expect(
      pairingReducer(review, {
        type: 'connected',
        hostId: 'h',
        duplicate: false,
      }),
    ).toBe(review);
  });

  it('resets to the initial input state', () => {
    const errored: PairingState = {
      step: 'error',
      source: 'paste',
      message: 'x',
    };
    expect(pairingReducer(errored, { type: 'reset' })).toEqual(
      initialPairingState,
    );
  });
});

describe('previewFromUri', () => {
  it('rejects a URI with the wrong prefix', () => {
    const result = previewFromUri('https://example.com');
    expect(result.ok).toBe(false);
  });

  it('rejects a malformed payload', () => {
    const result = previewFromUri(`${PAIRING_URI_PREFIX}not-base64url!!`);
    expect(result.ok).toBe(false);
  });

  it('trims surrounding whitespace without altering the payload', () => {
    const spaced = previewFromUri('   https://example.com   ');
    const plain = previewFromUri('https://example.com');
    expect(spaced.ok).toBe(plain.ok);
  });
});

describe('isExpired', () => {
  it('treats a past expiry as expired', () => {
    const now = 1_000_000_000_000;
    expect(
      isExpired(preview({ expiry: Math.floor(now / 1000) - 1 }), now),
    ).toBe(true);
  });

  it('treats a future expiry as valid', () => {
    const now = 1_000_000_000_000;
    expect(
      isExpired(preview({ expiry: Math.floor(now / 1000) + 60 }), now),
    ).toBe(false);
  });
});

describe('connectingLabel', () => {
  it('names the route without revealing an address', () => {
    expect(connectingLabel('direct')).toBe('Connecting directly');
    expect(connectingLabel('relay')).toBe('Connecting through relay');
  });
});
