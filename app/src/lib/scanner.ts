// The QR scanner's event vocabulary and its validation.
//
// Protocol, not presentation: these are the values the native view reports, so
// they live beside the other boundary code rather than inside a component.

/** Lifecycle states the native view reports. */
export type ScannerState = 'idle' | 'starting' | 'ready' | 'stopped' | 'error';

/** Bounded failure vocabulary. Anything unrecognized maps to 'unknown'. */
export type ScannerErrorCode =
  | 'no_activity'
  | 'no_camera'
  | 'permission_missing'
  | 'provider_failed'
  | 'bind_failed'
  | 'analyzer_failed'
  | 'unknown';

export interface ScannerStatus {
  state: ScannerState;
  code?: ScannerErrorCode;
  message?: string;
}

const STATES: readonly ScannerState[] = [
  'idle',
  'starting',
  'ready',
  'stopped',
  'error',
];

const CODES: readonly ScannerErrorCode[] = [
  'no_activity',
  'no_camera',
  'permission_missing',
  'provider_failed',
  'bind_failed',
  'analyzer_failed',
  'unknown',
];

// Native events are untrusted at this boundary: an unrecognized state or code
// becomes a generic error rather than leaking through as a bare string.
export function normalizeStatus(raw: {
  state?: string;
  code?: string;
  message?: string;
}): ScannerStatus {
  const state = STATES.find(s => s === raw.state) ?? 'error';
  const code = CODES.find(c => c === raw.code);
  const message =
    typeof raw.message === 'string' && raw.message !== ''
      ? raw.message.slice(0, 200)
      : undefined;
  if (state !== 'error') return { state };
  return { state, code: code ?? 'unknown', ...(message ? { message } : {}) };
}
