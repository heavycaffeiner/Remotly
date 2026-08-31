// App-level error model for Remotly.
//
// Errors that cross from the transport, terminal, or storage layers into the
// UI are normalized into `RemotlyError` so screens can render a stable,
// user-facing state without depending on the raw failure shape. The `message`
// field is always safe to display; the raw `cause` is kept only for logging
// (and must go through `log`, which redacts secrets).
//
// Pure module (no Node or native imports) so it runs under Hermes on both
// platforms.

export type RemotlyErrorKind =
  | 'network'
  | 'handshake'
  | 'auth'
  | 'protocol'
  | 'terminal'
  | 'storage'
  | 'unknown';

export interface RemotlyError {
  kind: RemotlyErrorKind;
  /** Stable, user-facing message. Safe to render directly. */
  message: string;
  /** Optional stable code (e.g. a protocol close code) for tooling. */
  code?: string | number;
  /** The original failure. Never rendered; only for redacted logging. */
  cause?: unknown;
}

// User-facing copy per error kind. Kept short and specific so a failed screen
// explains what happened and, where useful, what to do next.
const MESSAGES: Record<RemotlyErrorKind, string> = {
  network: 'Cannot reach the device. Check the network and try again.',
  handshake:
    'Secure connection failed. The device may be running a different version.',
  auth: 'Pairing was rejected. Check the pairing code and try again.',
  protocol: 'The connection closed unexpectedly. Reconnect to continue.',
  terminal: 'The terminal session stopped. Reopen the session to continue.',
  storage: 'Saved hosts could not be read. Your data has not been changed.',
  unknown: 'Something went wrong. Try again.',
};

// Maps a protocol close code (see docs/protocol.md) to an error kind. Codes
// 4000-4004 are the Remotly close range.
export function kindFromCloseCode(code: number): RemotlyErrorKind {
  switch (code) {
    case 4000:
      return 'protocol';
    case 4001:
      return 'auth';
    case 4002:
      return 'handshake';
    case 4003:
      return 'network';
    case 4004:
      return 'protocol';
    default:
      return 'unknown';
  }
}

// Runtime brand check. Class identity does not survive the native bridge, so a
// RemotlyError is recognized by its `__remotlyError` flag, set by
// `makeRemotlyError`.
function isRemotlyError(value: unknown): value is RemotlyErrorInstance {
  return (
    typeof value === 'object' &&
    value !== null &&
    (value as RemotlyErrorInstance).__remotlyError === true
  );
}

const KINDS: readonly RemotlyErrorKind[] = [
  'network',
  'handshake',
  'auth',
  'protocol',
  'terminal',
  'storage',
  'unknown',
];

function isKind(value: string): value is RemotlyErrorKind {
  return (KINDS as readonly string[]).includes(value);
}

// Normalizes an unknown thrown value into a RemotlyError. This is the single
// entry point the UI uses to turn a raw failure into a displayable state.
//
// Safety rule: only a branded RemotlyError (from `makeRemotlyError`) carries
// its own user-facing message. Any other value is an untrusted internal
// failure, so its raw message is never surfaced; the standardized copy for the
// resolved kind is used instead. The original value is kept as `cause` for
// redacted logging only.
export function toRemotlyError(
  value: unknown,
  fallbackKind: RemotlyErrorKind = 'unknown',
): RemotlyError {
  if (isRemotlyError(value)) return value;
  let kind = fallbackKind;
  let code: string | number | undefined;
  if (typeof value === 'object' && value !== null) {
    const obj = value as Record<string, unknown>;
    if (typeof obj.kind === 'string' && isKind(obj.kind)) kind = obj.kind;
    if (typeof obj.code === 'string' || typeof obj.code === 'number')
      code = obj.code;
  }
  return { kind, message: MESSAGES[kind], code, cause: value };
}

// Brand type recognized by `isRemotlyError` (no class, since class identity
// does not survive the native bridge).
export interface RemotlyErrorInstance extends RemotlyError {
  __remotlyError?: true;
}

export function makeRemotlyError(
  kind: RemotlyErrorKind,
  code?: string | number,
  cause?: unknown,
  message?: string,
): RemotlyErrorInstance {
  return {
    kind,
    code,
    cause,
    message: message ?? MESSAGES[kind],
    __remotlyError: true,
  };
}

export function userFacingMessage(err: RemotlyError): string {
  return err.message ?? MESSAGES[err.kind] ?? MESSAGES.unknown;
}
