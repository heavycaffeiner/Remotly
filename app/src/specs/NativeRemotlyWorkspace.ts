import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the per-host workspace store bridge (remotly.workspace.*). The
// document travels as one JSON string; the native side owns validation of the
// document shape (error code -3).

export interface LoadResult {
  /** The stored workspace document, or "" when none. */
  json: string;
}

export interface TakeOpenResult {
  /** The host the workspace page should open, or "" when none. */
  hostId: string;
}

export interface Spec extends TurboModule {
  /** Loads one host's workspace document. */
  load(hostId: string): Promise<LoadResult>;

  /** Replaces one host's workspace document. */
  save(hostId: string, json: string): Promise<void>;

  /** Deletes one host's workspace document. */
  clear(hostId: string): Promise<void>;

  /**
   * Frees the native terminal retained for a session.
   *
   * A terminal outlives the screen that rendered it so its scrollback survives
   * navigation. Closing the tab is what ends it; without this the scrollback
   * would be held for a session that can never be reopened.
   */
  releaseTerminal(sessionId: string): Promise<void>;

  /**
   * Stores the host the workspace page should open, for the take-once
   * handoff. Rejects (code -3) when the id is outside 1..64 characters.
   */
  open(hostId: string): Promise<void>;

  /** Drains the open handoff. One-shot; the second call returns "". */
  takeOpen(): Promise<TakeOpenResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyWorkspace');
