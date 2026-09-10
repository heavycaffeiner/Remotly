import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for the herdr bridge (remotly.herdr.*). One authenticated SSH
// connection is held per host and every command runs as a channel on it: the
// handshake was most of what a control call cost from a phone. exec runs one
// fully-quoted command line and returns its combined result; subscribe starts
// a command that keeps printing, which is how herdr's event stream reaches the
// app; release drops the connection. Bytes cross as standard base64 because
// the bridge carries no binary type; the JS layer decodes and parses.

export interface HerdrExecResult {
  /** True when the connect succeeded and the command ran; false on a
   *  connect-level failure, in which case code and message describe it. */
  ok: boolean;
  /** The command's exit status. Meaningful only when ok is true. */
  exitCode: number;
  /** Standard base64 of the command's stdout. */
  stdout: string;
  /** Standard base64 of the command's stderr. */
  stderr: string;
  /** The SshCode for a connect-level failure, empty when ok. */
  code: string;
  /** A human-readable failure, empty when ok. */
  message: string;
}

/** One line of a subscription's output, as it arrived. */
export type HerdrLineEvent = {
  hostId: string;
  line: string;
};

/** How a subscription ended. An empty code means the command ended itself. */
export type HerdrStreamEndEvent = {
  hostId: string;
  code: string;
  message: string;
};

export interface Spec extends TurboModule {
  /** Runs a fully quoted command line over SSH to a stored host. */
  exec(hostId: string, command: string): Promise<HerdrExecResult>;
  /**
   * Starts a command that keeps printing, delivering its stdout through
   * onLine and its end through onStreamEnd. This is what carries herdr's
   * event subscription; a command that cannot start reports through
   * onStreamEnd rather than rejecting.
   */
  subscribe(hostId: string, command: string): void;
  /** Closes the held connection to a host and everything running on it. */
  release(hostId: string): void;
  readonly onLine: EventEmitter<HerdrLineEvent>;
  readonly onStreamEnd: EventEmitter<HerdrStreamEndEvent>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyHerdr');
