import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the files-screen one-shot hand-off (remotly.files.*). The opening
// page stores the host to open (a JSON string of { hostId, kind }) immediately
// before navigating; the files page drains it on mount. The value is consumed
// once, so a stale signal from a previous open cannot leak into a later page.

export interface FilesTakeOpenResult {
  /** The stored open value, or "" when nothing was stored. */
  open: string;
}

export interface Spec extends TurboModule {
  /** Stores the one-shot open value for the files screen. */
  storeOpen(open: string): Promise<void>;

  /** Drains the one-shot open value; "" when nothing was stored. */
  takeOpen(): Promise<FilesTakeOpenResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyFiles');
