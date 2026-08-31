import { beforeEach, describe, expect, it, jest } from '@jest/globals';

// The transport TurboModule mock. The `mock` prefix on `mockListeners` lets
// jest's hoisting let the (hoisted) mock factory read it; the method mocks are
// captured from the mocked default export below.
const mockListeners = new Map<string, Set<(event: unknown) => void>>();

jest.mock('../../specs/NativeRemotlyTransport', () => {
  const register = (name: string) =>
    jest.fn((handler: (event: unknown) => void) => {
      let set = mockListeners.get(name);
      if (!set) {
        set = new Set();
        mockListeners.set(name, set);
      }
      set.add(handler);
      return {
        remove: () => {
          mockListeners.get(name)?.delete(handler);
        },
      };
    });
  return {
    __esModule: true,
    default: {
      connect: jest.fn(),
      close: jest.fn(),
      status: jest.fn(),
      control: jest.fn(),
      writeTerm: jest.fn(),
      openFile: jest.fn(),
      writeFile: jest.fn(),
      onConnected: register('connected'),
      onDisconnected: register('disconnected'),
      onSessionUpdate: register('sessionUpdate'),
      onChannelClose: register('channelClose'),
      onReplayComplete: register('replayComplete'),
      onTermData: register('termData'),
      onFileData: register('fileData'),
      onSessionEvent: register('sessionEvent'),
    },
  };
});

import NativeTransport from '../../specs/NativeRemotlyTransport';
import { decodeBase64, encodeBase64 } from '../base64';
import { getTransport, rfc3339ToMillis } from '../transport';

// Emits an event the way the codegen emitter delivers it: the payload object
// directly, with no wrapping envelope.
function emit(name: string, data: unknown): void {
  for (const cb of mockListeners.get(name) ?? []) cb(data);
}

// Builds a rejection the way a TurboModule surfaces a native failure: an Error
// whose `code` is the numeric bridge code as a string.
function rejectErr(code: number, msg: string): Error {
  const e = new Error(msg);
  (e as { code?: string }).code = String(code);
  return e;
}

const HOST = 'host-abc';

describe('transport (bridge-backed)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockListeners.clear();
  });

  it('is available and a stable singleton', () => {
    expect(getTransport()).toBe(getTransport());
    expect(getTransport().available).toBe(true);
  });

  it('connects with a pinned daemon key and resolves the daemon info', async () => {
    (
      NativeTransport.connect as jest.MockedFunction<
        typeof NativeTransport.connect
      >
    ).mockResolvedValue({
      daemonName: 'studio',
      daemonPub: 'A'.repeat(43),
    });
    const conn = await getTransport().connect(HOST, '192.168.0.10', {
      daemonPub: 'B'.repeat(43),
    });
    expect(conn).toEqual({ daemonName: 'studio', daemonPub: 'A'.repeat(43) });
    expect(NativeTransport.connect).toHaveBeenCalledWith(HOST, '192.168.0.10', {
      daemonPub: 'B'.repeat(43),
    });
  });

  it('connects with a pairing token and secret', async () => {
    (
      NativeTransport.connect as jest.MockedFunction<
        typeof NativeTransport.connect
      >
    ).mockResolvedValue({ daemonName: 'lab' });
    await getTransport().connect(HOST, '10.0.0.5:8788', {
      tokenID: 't'.repeat(22),
      psk: 's'.repeat(43),
    });
    expect(NativeTransport.connect).toHaveBeenCalledWith(
      HOST,
      '10.0.0.5:8788',
      {
        tokenID: 't'.repeat(22),
        psk: 's'.repeat(43),
      },
    );
  });

  it('rejects a blank hostId without calling the bridge', async () => {
    await expect(
      getTransport().connect('  ', '127.0.0.1', { daemonPub: 'B'.repeat(43) }),
    ).rejects.toMatchObject({ code: 'transport-invalid-params' });
    expect(NativeTransport.connect).not.toHaveBeenCalled();
  });

  it('rejects connect without any credentials, without calling the bridge', async () => {
    await expect(
      getTransport().connect(HOST, '127.0.0.1', {}),
    ).rejects.toMatchObject({
      kind: 'unknown',
      code: 'transport-invalid-params',
      __remotlyError: true,
    });
    expect(NativeTransport.connect).not.toHaveBeenCalled();
  });

  it('rejects a partial pairing (tokenID without psk)', async () => {
    await expect(
      getTransport().connect(HOST, '127.0.0.1', { tokenID: 't'.repeat(22) }),
    ).rejects.toMatchObject({ code: 'transport-invalid-params' });
    expect(NativeTransport.connect).not.toHaveBeenCalled();
  });

  it('rejects an empty target without calling the bridge', async () => {
    await expect(
      getTransport().connect(HOST, '   ', { daemonPub: 'B'.repeat(43) }),
    ).rejects.toMatchObject({ code: 'transport-invalid-params' });
    expect(NativeTransport.connect).not.toHaveBeenCalled();
  });

  it('maps a native auth failure to a branded auth error', async () => {
    (
      NativeTransport.connect as jest.MockedFunction<
        typeof NativeTransport.connect
      >
    ).mockRejectedValue(rejectErr(4001, 'auth failed'));
    await expect(
      getTransport().connect(HOST, '127.0.0.1', { daemonPub: 'B'.repeat(43) }),
    ).rejects.toMatchObject({
      kind: 'auth',
      code: 4001,
      __remotlyError: true,
    });
  });

  it('maps an abnormal close to a network error', async () => {
    (
      NativeTransport.connect as jest.MockedFunction<
        typeof NativeTransport.connect
      >
    ).mockRejectedValue(rejectErr(1006, 'abnormal'));
    await expect(
      getTransport().connect(HOST, '127.0.0.1', { daemonPub: 'B'.repeat(43) }),
    ).rejects.toMatchObject({ kind: 'network', code: 1006 });
  });

  it('never leaks credentials into a connect failure', async () => {
    const psk = 'a'.repeat(64);
    (
      NativeTransport.connect as jest.MockedFunction<
        typeof NativeTransport.connect
      >
    ).mockRejectedValue(rejectErr(4001, 'auth failed'));
    try {
      await getTransport().connect('secret-host', 'secret-host', {
        tokenID: 't'.repeat(22),
        psk,
      });
      throw new Error('should have rejected');
    } catch (e) {
      expect(JSON.stringify(e)).not.toContain(psk);
      expect(JSON.stringify(e)).not.toContain('secret-host');
    }
  });

  it('closes one host and resolves', async () => {
    (
      NativeTransport.close as jest.MockedFunction<typeof NativeTransport.close>
    ).mockResolvedValue(undefined);
    await expect(getTransport().close(HOST)).resolves.toBeUndefined();
    expect(NativeTransport.close).toHaveBeenCalledWith(HOST);
  });

  it('reads one host connection status', async () => {
    (
      NativeTransport.status as jest.MockedFunction<
        typeof NativeTransport.status
      >
    ).mockResolvedValue({
      connected: true,
      state: 'connected',
      daemonName: 'studio',
    });
    const status = await getTransport().status(HOST);
    expect(status).toEqual({
      connected: true,
      state: 'connected',
      daemonName: 'studio',
    });
    expect(NativeTransport.status).toHaveBeenCalledWith(HOST);
  });

  it('sends a control request as JSON and parses the response', async () => {
    (
      NativeTransport.control as jest.MockedFunction<
        typeof NativeTransport.control
      >
    ).mockResolvedValue({
      response: JSON.stringify({ type: 'session.create.response', id: 7 }),
    });
    const response = await getTransport().control(HOST, {
      type: 'session.create',
      title: 'sh',
    });
    expect(response).toEqual({ type: 'session.create.response', id: 7 });
    expect(NativeTransport.control).toHaveBeenCalledWith(
      HOST,
      JSON.stringify({ type: 'session.create', title: 'sh' }),
    );
  });

  it('rejects control when the daemon is unreachable', async () => {
    (
      NativeTransport.control as jest.MockedFunction<
        typeof NativeTransport.control
      >
    ).mockRejectedValue(rejectErr(0, 'not connected'));
    await expect(
      getTransport().control(HOST, { type: 'session.list' }),
    ).rejects.toMatchObject({
      code: 0,
      __remotlyError: true,
    });
  });

  it('writes terminal input as padded base64', async () => {
    (
      NativeTransport.writeTerm as jest.MockedFunction<
        typeof NativeTransport.writeTerm
      >
    ).mockResolvedValue(undefined);
    const bytes = new Uint8Array([0x01, 0x80, 0xff, 0x00, 0x02]);
    await getTransport().writeTerm(HOST, 3, bytes);
    expect(NativeTransport.writeTerm).toHaveBeenCalledWith(
      HOST,
      3,
      encodeBase64(bytes),
    );
  });

  it('resolves empty writeTerm input without calling the bridge', async () => {
    await expect(
      getTransport().writeTerm(HOST, 3, new Uint8Array(0)),
    ).resolves.toBeUndefined();
    expect(NativeTransport.writeTerm).not.toHaveBeenCalled();
  });

  it('rejects out-of-range channel ids without calling the bridge', async () => {
    for (const channelId of [0, -1, 2 ** 32, 1.5]) {
      await expect(
        getTransport().writeTerm(HOST, channelId, new Uint8Array([1])),
      ).rejects.toMatchObject({
        code: 'transport-invalid-params',
      });
    }
    expect(NativeTransport.writeTerm).not.toHaveBeenCalled();
  });

  it('rejects writeTerm when the hub is not connected', async () => {
    (
      NativeTransport.writeTerm as jest.MockedFunction<
        typeof NativeTransport.writeTerm
      >
    ).mockRejectedValue(rejectErr(0, 'not connected'));
    await expect(
      getTransport().writeTerm(HOST, 3, new Uint8Array([1])),
    ).rejects.toMatchObject({
      code: 0,
      __remotlyError: true,
    });
  });

  it('delivers termData as raw bytes with the host id', async () => {
    const seen: { hostId: string; channelId: number; data: Uint8Array }[] = [];
    const off = getTransport().onEvent('termData', e => seen.push(e));
    const bytes = new Uint8Array([0x1b, 0x5b, 0x48, 0x68, 0x69]);
    emit('termData', { hostId: HOST, channelId: 3, data: encodeBase64(bytes) });
    expect(seen).toHaveLength(1);
    expect(seen[0].hostId).toBe(HOST);
    expect(seen[0].channelId).toBe(3);
    expect(seen[0].data).toEqual(bytes);
    expect(decodeBase64(encodeBase64(bytes))).toEqual(bytes);
    off();
    emit('termData', { hostId: HOST, channelId: 3, data: encodeBase64(bytes) });
    expect(seen).toHaveLength(1);
  });

  it('delivers channelClose with a numeric channel id and host id', () => {
    let seen: { hostId: string; channelId: number; reason: string } | null =
      null;
    getTransport().onEvent('channelClose', e => {
      seen = e;
    });
    emit('channelClose', {
      hostId: HOST,
      channelId: 5,
      reason: 'session_exited',
    });
    expect(seen).toEqual({
      hostId: HOST,
      channelId: 5,
      reason: 'session_exited',
    });
  });

  it('delivers replayComplete with numeric channel id and offset', () => {
    let seen: { hostId: string; channelId: number; offset: number } | null =
      null;
    getTransport().onEvent('replayComplete', e => {
      seen = e;
    });
    emit('replayComplete', { hostId: HOST, channelId: 7, offset: '4096' });
    expect(seen).toEqual({ hostId: HOST, channelId: 7, offset: 4096 });
  });

  it('parses RFC3339 session timestamps to milliseconds', () => {
    expect(rfc3339ToMillis('2025-01-02T03:04:05Z')).toBe(
      Date.parse('2025-01-02T03:04:05Z'),
    );
    expect(rfc3339ToMillis('not a date')).toBe(0);
    expect(rfc3339ToMillis(123)).toBe(0);
    expect(rfc3339ToMillis(null)).toBe(0);
  });

  it('delivers sessionEvent with normalized fields', () => {
    let seen: unknown = null;
    getTransport().onEvent('sessionEvent', e => {
      seen = e;
    });
    emit('sessionEvent', {
      hostId: HOST,
      sessionId: '7',
      seq: '3',
      kind: 'pattern',
      pattern: 'error',
      text: 'Error: boom',
      ts: '1750000000',
    });
    expect(seen).toEqual({
      hostId: HOST,
      sessionId: '7',
      seq: 3,
      kind: 'pattern',
      pattern: 'error',
      text: 'Error: boom',
      ts: 1750000000,
    });
  });

  it('defaults sessionEvent kind to bell when malformed', () => {
    const seen: { kind: string; pattern?: string; text?: string }[] = [];
    getTransport().onEvent('sessionEvent', e => {
      seen.push({ kind: e.kind, pattern: e.pattern, text: e.text });
    });
    emit('sessionEvent', {
      hostId: HOST,
      sessionId: 1,
      seq: 1,
      kind: 'nonsense',
      ts: 5,
    });
    expect(seen).toHaveLength(1);
    expect(seen[0].kind).toBe('bell');
    expect(seen[0].pattern).toBeUndefined();
    expect(seen[0].text).toBeUndefined();
  });

  it('delivers connected, disconnected, and sessionUpdate events with host ids', () => {
    const events: Record<string, unknown> = {};
    getTransport().onEvent('connected', e => (events.connected = e));
    getTransport().onEvent('disconnected', e => (events.disconnected = e));
    getTransport().onEvent('sessionUpdate', e => (events.sessionUpdate = e));
    emit('connected', {
      hostId: HOST,
      daemonName: 'studio',
      daemonPub: 'A'.repeat(43),
    });
    emit('disconnected', { hostId: HOST, code: 1000, reason: 'closed' });
    emit('sessionUpdate', {
      hostId: HOST,
      session: {
        id: 7,
        title: 'sh',
        kind: 'pty',
        command: 'bash',
        cwd: '/',
        cols: 80,
        rows: 24,
        createdAt: 1,
        lastActivity: 2,
        running: true,
        preview: 'last line',
      },
    });
    expect(events.connected).toEqual({
      hostId: HOST,
      daemonName: 'studio',
      daemonPub: 'A'.repeat(43),
    });
    expect(events.disconnected).toEqual({
      hostId: HOST,
      code: 1000,
      reason: 'closed',
    });
    const su = events.sessionUpdate as {
      hostId: string;
      session: { id: number; preview?: string };
    };
    expect(su.hostId).toBe(HOST);
    expect(su.session.id).toBe(7);
    expect(su.session.preview).toBe('last line');
  });
});
