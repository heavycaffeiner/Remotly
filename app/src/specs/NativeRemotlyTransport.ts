import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for the daemon transport TurboModule. Failure promises reject with the
// numeric bridge code as a string: 4000-4004 are protocol close codes, 1006 is
// an abnormal close, 0 is a generic bridge failure, -3 an invalid parameter.

export interface ConnectOptions {
  /** base64url (unpadded) 32-byte daemon public key. Pinned-key path. */
  daemonPub?: string;
  /** base64url (unpadded) pairing token id. Pairing path. */
  tokenID?: string;
  /** base64url (unpadded) 32-byte pairing secret. Pairing path. */
  psk?: string;
  /** Relay fallback target ("host:port"), unpadded base64url relay id. */
  relayTarget?: string;
  relayId?: string;
  /** When true and a relay is configured, the relay is the only attempt. */
  relayOnly?: boolean;
}

export interface ConnectResult {
  daemonName: string;
  daemonPub?: string;
}

export interface StatusResult {
  connected: boolean;
  state: string;
  daemonName?: string;
  daemonPub?: string;
}

export interface ControlResult {
  /** The daemon's control response as a JSON string. */
  response: string;
}

export type ConnectedEvent = {
  hostId: string;
  daemonName: string;
  daemonPub: string;
  via?: string;
};

export type DisconnectedEvent = {
  hostId: string;
  code: number;
  reason: string;
};

export type SessionExit = {
  code: number;
  signal: string | null;
};

export type SessionMeta = {
  id: string;
  title: string;
  kind: string;
  command: string;
  cwd: string;
  cols: number;
  rows: number;
  createdAt: string;
  lastActivity: string;
  running: boolean;
  exit?: SessionExit | null;
  preview?: string | null;
};

export type SessionUpdateEvent = {
  hostId: string;
  session: SessionMeta;
};

export type ChannelCloseEvent = {
  hostId: string;
  channelId: number;
  reason: string;
};

export type ReplayCompleteEvent = {
  hostId: string;
  channelId: number;
  offset: number;
};

export type TermDataEvent = {
  hostId: string;
  channelId: number;
  /** Standard base64 (padded) of the raw terminal output bytes. */
  data: string;
};

export type FileDataEvent = {
  hostId: string;
  channelId: number;
  /** Standard base64 (padded) of one raw file-channel frame. */
  data: string;
};

export type SessionEventPayload = {
  hostId: string;
  sessionId: string;
  seq: number;
  kind: string;
  pattern?: string;
  text?: string;
  ts: number;
};

export interface Spec extends TurboModule {
  /**
   * Opens a secure connection for one host. Resolves when the handshake
   * (hello) completes. hostId is the app-side key; target is "host" or
   * "host:port".
   */
  connect(
    hostId: string,
    target: string,
    options: ConnectOptions,
  ): Promise<ConnectResult>;

  /** Closes one host's connection. A no-op success when none is open. */
  close(hostId: string): Promise<void>;

  /** Snapshot of one host's connection state. */
  status(hostId: string): Promise<StatusResult>;

  /**
   * Sends one control request (a JSON object string) and resolves with the
   * daemon's response as a JSON string. A protocol-level error is inside the
   * response JSON and still resolves; the promise rejects only when the
   * request could not complete.
   */
  control(hostId: string, request: string): Promise<ControlResult>;

  /**
   * Writes terminal input (standard base64) to an attached channel, fire and
   * forget. A write dropped because no channel is attached still resolves.
   */
  writeTerm(hostId: string, channelId: number, data: string): Promise<void>;

  /**
   * Registers the file channel a transfer.create/resume opened. No-op success
   * when there is no ready connection.
   */
  openFile(hostId: string, channelId: number): Promise<void>;

  /**
   * Writes one file-channel frame (standard base64 of
   * [8-byte big-endian offset][payload]) to the daemon, fire and forget.
   */
  writeFile(hostId: string, channelId: number, data: string): Promise<void>;

  readonly onConnected: EventEmitter<ConnectedEvent>;
  readonly onDisconnected: EventEmitter<DisconnectedEvent>;
  readonly onSessionUpdate: EventEmitter<SessionUpdateEvent>;
  readonly onChannelClose: EventEmitter<ChannelCloseEvent>;
  readonly onReplayComplete: EventEmitter<ReplayCompleteEvent>;
  readonly onTermData: EventEmitter<TermDataEvent>;
  readonly onFileData: EventEmitter<FileDataEvent>;
  readonly onSessionEvent: EventEmitter<SessionEventPayload>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyTransport');
