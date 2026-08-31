import { beforeEach, describe, expect, it, jest } from '@jest/globals';

// Shared state the '../transport' mock factory reads. The `mock` prefix is
// required so jest's hoisting lets the factory reference it.
const mockState = {
  requests: [] as Record<string, unknown>[],
  responder: null as null | ((request: Record<string, unknown>) => unknown),
};

// The real transport module imports the transport TurboModule spec; mock it so
// `requireActual` can load transport.ts in the jest environment.
jest.mock('../../specs/NativeRemotlyTransport', () => ({
  __esModule: true,
  default: {
    connect: jest.fn(),
    close: jest.fn(),
    status: jest.fn(),
    control: jest.fn(),
    writeTerm: jest.fn(),
    openFile: jest.fn(),
    writeFile: jest.fn(),
    onConnected: jest.fn(),
    onDisconnected: jest.fn(),
    onSessionUpdate: jest.fn(),
    onChannelClose: jest.fn(),
    onReplayComplete: jest.fn(),
    onTermData: jest.fn(),
    onFileData: jest.fn(),
    onSessionEvent: jest.fn(),
  },
}));

jest.mock('../transport', () => {
  const actual =
    jest.requireActual<typeof import('../transport')>('../transport');
  return {
    ...actual,
    getTransport: () => ({
      control: (_hostId: string, request: Record<string, unknown>) => {
        mockState.requests.push(request);
        return Promise.resolve(
          mockState.responder ? mockState.responder(request) : {},
        );
      },
    }),
  };
});

import {
  attachSession,
  createSession,
  DaemonError,
  detachChannel,
  listPresets,
  listSessions,
  parseRawSession,
  rawSessionLastActivityMs,
  resizeSession,
} from '../sessions';

const SID = 'a'.repeat(64);
const OTHER = 'b'.repeat(64);

function meta(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    title: 'shell',
    kind: 'shell',
    command: '',
    cwd: '/home/u',
    cols: 80,
    rows: 24,
    created_at: '2025-01-02T03:04:05Z',
    last_activity: '2025-01-02T03:05:06Z',
    running: true,
    ...extra,
  };
}

beforeEach(() => {
  mockState.requests.length = 0;
  mockState.responder = null;
});

describe('parseRawSession', () => {
  it('accepts a well-formed row and clamps strings', () => {
    const s = parseRawSession(
      meta(SID, { title: 'x'.repeat(500), preview: 'ok' }),
    );
    expect(s).not.toBeNull();
    expect(s!.id).toBe(SID);
    expect(s!.title).toHaveLength(200);
    expect(s!.running).toBe(true);
    expect(s!.preview).toBe('ok');
  });

  it('rejects a malformed id and a non-object', () => {
    expect(parseRawSession(meta('12345'))).toBeNull();
    expect(parseRawSession(null)).toBeNull();
    expect(parseRawSession('nope')).toBeNull();
  });

  it('drops a malformed exit and keeps the row', () => {
    const s = parseRawSession(
      meta(SID, { running: false, exit: { code: 'nope' } }),
    );
    expect(s).not.toBeNull();
    expect(s!.running).toBe(false);
    expect(s!.exit).toBeUndefined();
  });

  it('keeps a valid exit', () => {
    const s = parseRawSession(
      meta(SID, { running: false, exit: { code: 3, signal: null } }),
    );
    expect(s!.exit).toEqual({ code: 3, signal: null });
  });

  it('converts the RFC3339 last activity to milliseconds', () => {
    const s = parseRawSession(meta(SID))!;
    expect(rawSessionLastActivityMs(s)).toBe(
      Date.parse('2025-01-02T03:05:06Z'),
    );
    expect(
      rawSessionLastActivityMs(
        parseRawSession(meta(SID, { last_activity: 'nope' }))!,
      ),
    ).toBe(0);
  });
});

describe('listSessions', () => {
  it('sends the raw request and parses valid rows only', async () => {
    mockState.responder = () => ({
      id: 1,
      type: 'session.list',
      sessions: [
        meta(SID),
        { id: 'zzz' },
        meta(SID),
        meta(OTHER, { running: false }),
      ],
    });
    const sessions = await listSessions('host1');
    expect(mockState.requests[0]).toEqual({ type: 'session.list' });
    expect(sessions.map(s => s.id)).toEqual([SID, OTHER]);
  });

  it('surfaces a daemon error response as DaemonError', async () => {
    mockState.responder = () => ({
      id: 1,
      type: 'error',
      error: { code: 'unknown_session', message: 'no such session' },
    });
    await expect(listSessions('host1')).rejects.toMatchObject({
      code: 'unknown_session',
    });
  });

  it('tolerates a missing sessions array', async () => {
    mockState.responder = () => ({ id: 1, type: 'session.list' });
    await expect(listSessions('host1')).resolves.toEqual([]);
  });
});

describe('attachSession', () => {
  it('attaches with a resume cursor and validates the response', async () => {
    mockState.responder = () => ({
      id: 2,
      type: 'session.attach',
      channel_id: 7,
      continuity: 'gapless',
      replayed_from: 4096,
    });
    const result = await attachSession('host1', SID, 4096);
    expect(mockState.requests[0]).toEqual({
      type: 'session.attach',
      session_id: SID,
      resume_from: 4096,
    });
    expect(result).toEqual({
      channelId: 7,
      continuity: 'gapless',
      replayedFrom: 4096,
    });
  });

  it('omits resume_from when not provided', async () => {
    mockState.responder = () => ({
      id: 2,
      type: 'session.attach',
      channel_id: 1,
      continuity: 'full',
      replayed_from: 0,
    });
    await attachSession('host1', SID);
    expect(mockState.requests[0]).toEqual({
      type: 'session.attach',
      session_id: SID,
    });
  });

  it('propagates cursor_out_of_range for a retry without cursor', async () => {
    mockState.responder = () => ({
      id: 2,
      type: 'error',
      error: { code: 'cursor_out_of_range', message: 'cursor not in window' },
    });
    await expect(attachSession('host1', SID, 99)).rejects.toBeInstanceOf(
      DaemonError,
    );
    await expect(attachSession('host1', SID, 99)).rejects.toMatchObject({
      code: 'cursor_out_of_range',
    });
  });

  it('rejects a bad cursor before touching the wire', async () => {
    await expect(attachSession('host1', SID, -1)).rejects.toMatchObject({
      code: 'invalid_request',
    });
    expect(mockState.requests).toHaveLength(0);
  });

  it('rejects a malformed response', async () => {
    mockState.responder = () => ({
      id: 2,
      type: 'session.attach',
      channel_id: 0,
      continuity: 'full',
      replayed_from: 0,
    });
    await expect(attachSession('host1', SID)).rejects.toMatchObject({
      code: 'invalid_request',
    });
  });
});

describe('createSession', () => {
  it('creates a shell without a command', async () => {
    mockState.responder = () => ({
      id: 3,
      type: 'session.create',
      session: meta(SID),
    });
    const s = await createSession('host1', { kind: 'shell', title: 'shell' });
    expect(mockState.requests[0]).toEqual({
      type: 'session.create',
      kind: 'shell',
      title: 'shell',
    });
    expect(s.id).toBe(SID);
  });

  it('creates an agent session from a preset command', async () => {
    mockState.responder = () => ({
      id: 3,
      type: 'session.create',
      session: meta(OTHER),
    });
    const s = await createSession('host1', {
      kind: 'agent',
      command: 'agent --task fix',
      title: 'Fix',
    });
    expect(mockState.requests[0]).toEqual({
      type: 'session.create',
      kind: 'agent',
      command: 'agent --task fix',
      title: 'Fix',
    });
    expect(s.id).toBe(OTHER);
  });

  it('rejects an agent session without a command', async () => {
    await expect(
      createSession('host1', { kind: 'agent' }),
    ).rejects.toMatchObject({
      code: 'invalid_request',
    });
    expect(mockState.requests).toHaveLength(0);
  });

  it('rejects a malformed create response', async () => {
    mockState.responder = () => ({
      id: 3,
      type: 'session.create',
      session: { id: 'nope' },
    });
    await expect(
      createSession('host1', { kind: 'shell' }),
    ).rejects.toMatchObject({
      code: 'invalid_request',
    });
  });
});

describe('detachChannel and resizeSession', () => {
  it('sends the raw requests', async () => {
    mockState.responder = () => ({ id: 4, type: 'session.detach' });
    await detachChannel('host1', 9);
    expect(mockState.requests[0]).toEqual({
      type: 'session.detach',
      channel_id: 9,
    });

    mockState.responder = () => ({ id: 5, type: 'session.resize' });
    await resizeSession('host1', SID, 100, 50);
    expect(mockState.requests[1]).toEqual({
      type: 'session.resize',
      session_id: SID,
      cols: 100,
      rows: 50,
    });
  });

  it('rejects out-of-range channel ids and dimensions', async () => {
    await expect(detachChannel('host1', 0)).rejects.toMatchObject({
      code: 'invalid_request',
    });
    await expect(resizeSession('host1', SID, 0, 24)).rejects.toMatchObject({
      code: 'invalid_request',
    });
    expect(mockState.requests).toHaveLength(0);
  });
});

describe('listPresets', () => {
  it('parses presets and drops malformed or duplicate rows', async () => {
    mockState.responder = () => ({
      id: 6,
      type: 'preset.list',
      presets: [
        { name: 'Fix', command: 'agent --task fix', icon_hint: 'wrench' },
        { name: 'Fix', command: 'agent --task fix', icon_hint: 'wrench' },
        { name: '', command: 'x', icon_hint: '' },
        { name: 'NoCmd', command: '', icon_hint: '' },
        null,
      ],
    });
    const presets = await listPresets('host1');
    expect(presets).toEqual([
      { name: 'Fix', command: 'agent --task fix', icon_hint: 'wrench' },
    ]);
  });

  it('tolerates a missing presets array', async () => {
    mockState.responder = () => ({ id: 6, type: 'preset.list' });
    await expect(listPresets('host1')).resolves.toEqual([]);
  });
});
