import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Writes into a terminal that has no view attached (remotly.terminalStore.*).
//
// A tab that is not on screen still receives output. Queueing it in JS means
// choosing what to discard when the queue fills, and a queue trimmed from the
// front cuts an escape sequence in half, so what survives renders as garbage
// rather than as older text. Feeding the terminal directly avoids the choice:
// it owns a bounded scrollback and a parser that understands the stream.

export interface Spec extends TurboModule {
  /**
   * Writes base64 output into the terminal retained for `sessionId`.
   *
   * A session with no terminal yet is given one, sized to `cols` by `rows`,
   * so output for a tab that has never been opened is kept rather than queued
   * for as long as it stays unopened.
   *
   * Resolves once the write has happened, which is what paces the caller:
   * resolving at once lets a busy session queue writes faster than they run.
   */
  feed(
    sessionId: string,
    data: string,
    cols: number,
    rows: number,
  ): Promise<boolean>;

  /** True when a terminal is retained for the session. */
  has(sessionId: string): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyTerminalStore');
