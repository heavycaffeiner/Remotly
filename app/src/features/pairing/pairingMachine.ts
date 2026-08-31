// The pairing flow as a pure state machine.
//
// Steps: input, review, connecting, success, error. Keeping the transitions
// out of the component makes the retry and dedupe rules testable, which is
// where the previous implementation went wrong: a URI that failed could never
// be tried again.

import { encodeBase64Url } from '../../lib/base64url';
import {
  PAIRING_ERROR_TEXT,
  parsePairingURI,
  type PairingHint,
  type PairingPayload,
  type PairingRelay,
} from '../../lib/pairing';
import type { ScannerStatus } from '../../lib/scanner';

export type PairingMethod = 'scan' | 'paste';

export interface PairingPreview {
  daemonName: string;
  /** A short form of the pinned daemon key. Display only. */
  fingerprint: string;
  /** Unix seconds. */
  expiry: number;
  targets: string[];
  usableHints: PairingHint[];
  relay: PairingRelay | null;
  payload: PairingPayload;
}

/** What the app is currently attempting, for a non-technical progress line. */
export type ConnectRoute = 'direct' | 'relay';

export type PairingState =
  | { step: 'input'; method: PairingMethod; scanner: ScannerStatus }
  | { step: 'review'; preview: PairingPreview }
  | { step: 'connecting'; preview: PairingPreview; route: ConnectRoute }
  | { step: 'success'; hostId: string; daemonName: string; duplicate: boolean }
  | {
      step: 'error';
      source: 'scan' | 'paste' | 'connect';
      message: string;
      /** Present when the user can go back to a parsed identity. */
      preview?: PairingPreview;
    };

export type PairingAction =
  | { type: 'method'; method: PairingMethod }
  | { type: 'scannerStatus'; status: ScannerStatus }
  | { type: 'parsed'; preview: PairingPreview }
  | {
      type: 'parseFailed';
      source: 'scan' | 'paste';
      message: string;
    }
  | { type: 'connect'; route: ConnectRoute }
  | { type: 'connectRoute'; route: ConnectRoute }
  | { type: 'connectFailed'; message: string }
  | { type: 'connected'; hostId: string; duplicate: boolean }
  | { type: 'reset' };

export const initialPairingState: PairingState = {
  step: 'input',
  method: 'scan',
  scanner: { state: 'idle' },
};

export function pairingReducer(
  state: PairingState,
  action: PairingAction,
): PairingState {
  switch (action.type) {
    case 'method':
      if (state.step !== 'input') return state;
      return { ...state, method: action.method };

    case 'scannerStatus':
      if (state.step !== 'input') return state;
      return { ...state, scanner: action.status };

    case 'parsed':
      // A parse can only be accepted from input or from an error, never while
      // a connection attempt is already running.
      if (state.step !== 'input' && state.step !== 'error') return state;
      return { step: 'review', preview: action.preview };

    case 'parseFailed':
      if (state.step !== 'input' && state.step !== 'error') return state;
      return { step: 'error', source: action.source, message: action.message };

    case 'connect':
      if (state.step !== 'review') return state;
      return {
        step: 'connecting',
        preview: state.preview,
        route: action.route,
      };

    case 'connectRoute':
      if (state.step !== 'connecting') return state;
      return { ...state, route: action.route };

    case 'connectFailed':
      if (state.step !== 'connecting') return state;
      return {
        step: 'error',
        source: 'connect',
        message: action.message,
        preview: state.preview,
      };

    case 'connected':
      if (state.step !== 'connecting') return state;
      return {
        step: 'success',
        hostId: action.hostId,
        daemonName: state.preview.daemonName,
        duplicate: action.duplicate,
      };

    case 'reset':
      return initialPairingState;

    default:
      return state;
  }
}

/** True while the native scanner should be paused. */
export function scannerPaused(state: PairingState): boolean {
  return state.step !== 'input';
}

/** Parses a URI into a preview, or into stable user-facing error copy. */
export function previewFromUri(
  uri: string,
): { ok: true; preview: PairingPreview } | { ok: false; message: string } {
  // Only surrounding whitespace is stripped. The payload itself is never
  // altered, because a modified payload would pair against a different key.
  const parsed = parsePairingURI(uri.trim());
  if (!parsed.ok) {
    return { ok: false, message: PAIRING_ERROR_TEXT[parsed.error] };
  }
  return {
    ok: true,
    preview: {
      daemonName: parsed.payload.daemonName,
      fingerprint: `${encodeBase64Url(parsed.payload.daemonPub).slice(0, 20)}…`,
      expiry: parsed.payload.expires,
      targets: parsed.targets,
      usableHints: parsed.usableHints,
      relay: parsed.relay,
      payload: parsed.payload,
    },
  };
}

/** A pairing code that has already expired can never complete. */
export function isExpired(
  preview: PairingPreview,
  nowMs: number = Date.now(),
): boolean {
  return preview.expiry * 1000 <= nowMs;
}

export function formatExpiry(unixSeconds: number): string {
  return new Date(unixSeconds * 1000).toLocaleString();
}

/** Progress copy that names the route without revealing target addresses. */
export function connectingLabel(route: ConnectRoute): string {
  return route === 'relay' ? 'Connecting through relay' : 'Connecting directly';
}
