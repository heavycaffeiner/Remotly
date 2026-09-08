import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the herdr bridge (remotly.herdr.*). One method: exec. It runs a
// single fully-quoted command line over a fresh SSH connection to a stored
// host and returns the combined result. The herdr control calls the app makes
// (session list, api snapshot, workspace create, pane read, agent prompt) all
// finish and disconnect, so each is a separate one-shot exec rather than a
// long-lived session. Bytes cross as standard base64 because the bridge
// carries no binary type; the JS layer decodes and parses.

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

export interface Spec extends TurboModule {
  /** Runs a fully quoted command line over SSH to a stored host. */
  exec(hostId: string, command: string): Promise<HerdrExecResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyHerdr');
