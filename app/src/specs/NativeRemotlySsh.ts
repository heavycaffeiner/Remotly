import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for the SSH terminal bridge (remotly.ssh.*). Sessions are keyed by
// (hostId, sessionId), so one host can back several terminal tabs: connect
// starts a session and returns immediately; state changes and terminal bytes
// arrive through the onState and onData emitters, each carrying both ids. The
// host-key flow is poll/answer: connect may park in a "hostKey" state, and the
// app answers with hostKey(hostId, sessionId, decision). Method names and the
// state vocabulary mirror SshSession.kt.

export type SshHostKeyDecision = 'accept' | 'replace' | 'reject';

export type SshStateEvent = {
  hostId: string;
  /** The terminal tab this event belongs to. */
  sessionId: string;
  // The SshSessionState kind: disconnected | connecting | hostKey | active |
  // closed | failed.
  state: string;
  // hostKey prompt.
  algorithm?: string;
  fingerprint?: string;
  changed?: boolean;
  /** The close code as a string: a CloseCode number (1000/1001) for
   *  closed, or an SshCode string for failed. */
  code?: string;
  /**
   * The operation that failed: dial, handshake, auth, host key, channel, pty,
   * or shell. Empty when the engine reports none. `code` stays stable for
   * error mapping; the stage is what makes a failure diagnosable.
   */
  stage?: string;
  reason?: string;
  userInitiated?: boolean;
};

export type SshDataEvent = {
  hostId: string;
  /** The terminal tab this output belongs to. */
  sessionId: string;
  /** Standard base64 of the raw terminal output bytes. */
  data: string;
};

export interface Spec extends TurboModule {
  /**
   * Starts a terminal to a stored host and returns immediately. Reads the host
   * and credential by id natively; JS passes the host id and the tab's session
   * id. State and output arrive through onState and onData.
   *
   * `sessionId` is app-minted and identifies one tab. Reconnecting a tab
   * reuses its id, which replaces that tab's session and leaves the others
   * untouched.
   */
  connect(
    hostId: string,
    sessionId: string,
    cols: number,
    rows: number,
  ): Promise<void>;

  /** Writes terminal input (standard base64) to the session, fire and forget. */
  write(hostId: string, sessionId: string, data: string): Promise<void>;

  /** Sends a PTY window-change (a real SSH request). */
  resize(
    hostId: string,
    sessionId: string,
    cols: number,
    rows: number,
  ): Promise<void>;

  /**
   * Answers a host-key prompt. decision is "accept" (first-use), "replace"
   * (intentionally accept a changed key), or "reject".
   */
  hostKey(hostId: string, sessionId: string, decision: string): Promise<void>;

  /** Closes one session. A no-op when it is not open. */
  close(hostId: string, sessionId: string): Promise<void>;

  /** Closes every session for a host. Used when the host is deleted. */
  closeHost(hostId: string): Promise<void>;

  /** Stores the one-shot host id the terminal screen should open. */
  storeOpen(hostId: string): Promise<void>;

  /** Drains the one-shot host id; "" when nothing was stored. */
  takeOpen(): Promise<{ hostId: string }>;

  readonly onState: EventEmitter<SshStateEvent>;
  readonly onData: EventEmitter<SshDataEvent>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlySsh');
