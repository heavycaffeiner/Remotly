// SSH event routing: every listener is bound to one host id, so two mounted
// SSH terminal screens cannot consume each other's state or terminal output.

// The jest.mock factory is hoisted, so anything it touches needs the `mock`
// prefix to be reachable from inside it.
// A function declaration, not a const: the factory runs before a const in
// this module would be initialized.
function mockListenerMap(): Map<string, Set<(e: unknown) => void>> {
  const g = globalThis as {
    __sshListeners?: Map<string, Set<(e: unknown) => void>>;
  };
  g.__sshListeners ??= new Map();
  return g.__sshListeners;
}

function mockEmitter(name: string) {
  return jest.fn((cb: (e: unknown) => void) => {
    const map = mockListenerMap();
    const set = map.get(name) ?? new Set<(e: unknown) => void>();
    set.add(cb);
    map.set(name, set);
    return {
      remove: () => {
        map.get(name)?.delete(cb);
      },
    };
  });
}

function emit(name: string, payload: unknown): void {
  for (const cb of mockListenerMap().get(name) ?? []) cb(payload);
}

function listenerCount(name: string): number {
  return mockListenerMap().get(name)?.size ?? 0;
}

jest.mock('../../specs/NativeRemotlySsh', () => ({
  __esModule: true,
  default: {
    connect: jest.fn().mockResolvedValue(undefined),
    write: jest.fn().mockResolvedValue(undefined),
    resize: jest.fn().mockResolvedValue(undefined),
    hostKey: jest.fn().mockResolvedValue(undefined),
    close: jest.fn().mockResolvedValue(undefined),
    storeOpen: jest.fn().mockResolvedValue(undefined),
    takeOpen: jest.fn().mockResolvedValue({ hostId: '' }),
    onState: mockEmitter('state'),
    onData: mockEmitter('data'),
  },
}));

import NativeSsh from '../../specs/NativeRemotlySsh';
import { encodeBase64 } from '../base64';
import { eventHostId, normalizeState, remotlySsh, stageMessage } from '../ssh';

beforeEach(() => {
  mockListenerMap().clear();
});

describe('eventHostId', () => {
  it('reads the host id from an event', () => {
    expect(eventHostId({ hostId: 'h1' })).toBe('h1');
  });

  it('returns an empty string for a malformed event', () => {
    expect(eventHostId(null)).toBe('');
    expect(eventHostId(undefined)).toBe('');
    expect(eventHostId({})).toBe('');
    expect(eventHostId({ hostId: 7 })).toBe('');
    expect(eventHostId('nope')).toBe('');
  });
});

describe('normalizeState', () => {
  it('maps a known state kind', () => {
    expect(normalizeState({ state: 'active' })).toEqual({ state: 'active' });
  });

  it('rejects an unknown state kind rather than passing it through', () => {
    expect(normalizeState({ state: 'compromised' }).state).toBe('disconnected');
    expect(normalizeState({}).state).toBe('disconnected');
  });

  it('carries the host key prompt fields', () => {
    const out = normalizeState({
      state: 'hostKey',
      algorithm: 'ssh-ed25519',
      fingerprint: 'SHA256:abc',
      changed: true,
    });
    expect(out).toEqual({
      state: 'hostKey',
      algorithm: 'ssh-ed25519',
      fingerprint: 'SHA256:abc',
      changed: true,
    });
  });

  it('stringifies a numeric close code', () => {
    expect(normalizeState({ state: 'closed', code: 1000 }).code).toBe('1000');
  });

  it('carries a known failure stage', () => {
    const out = normalizeState({
      state: 'failed',
      code: 'ssh_connect_failed',
      stage: 'ssh_dial_failed',
    });
    expect(out.stage).toBe('ssh_dial_failed');
  });

  it('drops an unrecognized stage rather than passing it through', () => {
    expect(
      normalizeState({ state: 'failed', stage: 'made_up' }).stage,
    ).toBeUndefined();
    expect(normalizeState({ state: 'failed' }).stage).toBeUndefined();
  });
});

describe('stageMessage', () => {
  it('distinguishes a refused dial from a rejected handshake', () => {
    // Both arrive as ssh_connect_failed, which is why the stage exists.
    const dial = stageMessage('ssh_dial_failed');
    const handshake = stageMessage('ssh_handshake_failed');
    expect(dial).not.toBeNull();
    expect(handshake).not.toBeNull();
    expect(dial).not.toEqual(handshake);
  });

  it('explains a refused terminal', () => {
    expect(stageMessage('ssh_pty_failed')).toContain('terminal');
  });

  it('returns null when there is no stage', () => {
    expect(stageMessage(undefined)).toBeNull();
  });

  it('returns null for a stage with no dedicated copy', () => {
    // A cancelled session is not an error the user needs explained.
    expect(stageMessage('ssh_cancelled')).toBeNull();
  });
});

describe('per-session state routing', () => {
  it('delivers only this session events', () => {
    const seen: string[] = [];
    remotlySsh.onState('h1', 's1', s => seen.push(s.state));

    emit('state', { hostId: 'h1', sessionId: 's1', state: 'connecting' });
    emit('state', { hostId: 'h2', sessionId: 's1', state: 'active' });
    emit('state', { hostId: 'h1', sessionId: 's1', state: 'active' });

    expect(seen).toEqual(['connecting', 'active']);
  });

  it('drops an event that carries no host id', () => {
    const seen: string[] = [];
    remotlySsh.onState('h1', 's1', s => seen.push(s.state));
    emit('state', { sessionId: 's1', state: 'active' });
    expect(seen).toEqual([]);
  });

  it('drops an event that carries no session id', () => {
    const seen: string[] = [];
    remotlySsh.onState('h1', 's1', s => seen.push(s.state));
    emit('state', { hostId: 'h1', state: 'active' });
    expect(seen).toEqual([]);
  });

  it('keeps two tabs on one host separate', () => {
    const a: string[] = [];
    const b: string[] = [];
    remotlySsh.onState('h1', 's1', s => a.push(s.state));
    remotlySsh.onState('h1', 's2', s => b.push(s.state));

    emit('state', { hostId: 'h1', sessionId: 's1', state: 'active' });
    emit('state', { hostId: 'h1', sessionId: 's2', state: 'failed' });

    expect(a).toEqual(['active']);
    expect(b).toEqual(['failed']);
  });

  it('keeps two hosts separate', () => {
    const a: string[] = [];
    const b: string[] = [];
    remotlySsh.onState('h1', 's1', s => a.push(s.state));
    remotlySsh.onState('h2', 's1', s => b.push(s.state));

    emit('state', { hostId: 'h1', sessionId: 's1', state: 'active' });
    emit('state', { hostId: 'h2', sessionId: 's1', state: 'failed' });

    expect(a).toEqual(['active']);
    expect(b).toEqual(['failed']);
  });

  it('stops delivering after unsubscribe', () => {
    const seen: string[] = [];
    const off = remotlySsh.onState('h1', 's1', s => seen.push(s.state));
    emit('state', { hostId: 'h1', sessionId: 's1', state: 'active' });
    off();
    emit('state', { hostId: 'h1', sessionId: 's1', state: 'closed' });
    expect(seen).toEqual(['active']);
    expect(listenerCount('state')).toBe(0);
  });

  it('does not let one failing handler stop another listener', () => {
    const seen: string[] = [];
    remotlySsh.onState('h1', 's1', () => {
      throw new Error('handler blew up');
    });
    remotlySsh.onState('h1', 's2', s => seen.push(s.state));
    emit('state', { hostId: 'h1', sessionId: 's1', state: 'active' });
    emit('state', { hostId: 'h1', sessionId: 's2', state: 'active' });
    expect(seen).toEqual(['active']);
  });
});

describe('per-session data routing', () => {
  it('decodes only this session output', () => {
    const chunks: Uint8Array[] = [];
    remotlySsh.onData('h1', 's1', b => chunks.push(b));

    const mine = Uint8Array.from([0x68, 0x69]);
    emit('data', { hostId: 'h1', sessionId: 's1', data: encodeBase64(mine) });
    emit('data', {
      hostId: 'h2',
      sessionId: 's1',
      data: encodeBase64(Uint8Array.from([0x62, 0x61, 0x64])),
    });

    expect(chunks).toHaveLength(1);
    expect(Array.from(chunks[0])).toEqual([0x68, 0x69]);
  });

  // The regression this keying exists to prevent: one tab's output rendered
  // into another tab's terminal.
  it('never crosses output between two tabs on one host', () => {
    const a: Uint8Array[] = [];
    const b: Uint8Array[] = [];
    remotlySsh.onData('h1', 's1', x => a.push(x));
    remotlySsh.onData('h1', 's2', x => b.push(x));

    emit('data', {
      hostId: 'h1',
      sessionId: 's1',
      data: encodeBase64(Uint8Array.from([0x61])),
    });
    emit('data', {
      hostId: 'h1',
      sessionId: 's2',
      data: encodeBase64(Uint8Array.from([0x62])),
    });

    expect(a.map(x => Array.from(x))).toEqual([[0x61]]);
    expect(b.map(x => Array.from(x))).toEqual([[0x62]]);
  });

  it('ignores an empty payload', () => {
    const chunks: Uint8Array[] = [];
    remotlySsh.onData('h1', 's1', b => chunks.push(b));
    emit('data', { hostId: 'h1', sessionId: 's1', data: '' });
    emit('data', { hostId: 'h1', sessionId: 's1' });
    expect(chunks).toHaveLength(0);
  });

  it('survives an undecodable payload', () => {
    const chunks: Uint8Array[] = [];
    remotlySsh.onData('h1', 's1', b => chunks.push(b));
    emit('data', {
      hostId: 'h1',
      sessionId: 's1',
      data: '!!!not base64!!!',
    });
    emit('data', {
      hostId: 'h1',
      sessionId: 's1',
      data: encodeBase64(Uint8Array.from([0x6f, 0x6b])),
    });
    expect(chunks).toHaveLength(1);
    expect(Array.from(chunks[0])).toEqual([0x6f, 0x6b]);
  });
});

describe('write', () => {
  it('skips a zero-length write', async () => {
    (NativeSsh.write as jest.Mock).mockClear();
    await remotlySsh.write('h1', 's1', new Uint8Array());
    expect(NativeSsh.write).not.toHaveBeenCalled();
  });

  it('base64-encodes a real write', async () => {
    (NativeSsh.write as jest.Mock).mockClear();
    await remotlySsh.write('h1', 's1', Uint8Array.from([0x68, 0x69]));
    expect(NativeSsh.write).toHaveBeenCalledWith(
      'h1',
      's1',
      encodeBase64(Uint8Array.from([0x68, 0x69])),
    );
  });
});
