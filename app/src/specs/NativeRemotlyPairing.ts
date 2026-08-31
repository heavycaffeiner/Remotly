import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the pairing deep-link handoff (remotly.pairing.*).

export interface TakePendingResult {
  /** The pending remotly://pair URI, or "" when none is pending. */
  uri: string;
}

export interface Spec extends TurboModule {
  /**
   * Drains the pending deep-link pairing URI, if any. One-shot: each call
   * returns the current value and clears it.
   */
  takePending(): Promise<TakePendingResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyPairing');
