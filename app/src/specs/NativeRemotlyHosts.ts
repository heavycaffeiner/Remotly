import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the persisted-host store bridge (remotly.hosts.*). Records travel
// as one JSON string so the bridge carries no nested object models.

export interface AddResult {
  /** The stored host id (the daemon public key). */
  id: string;
  /** True when an existing record with the same key was refreshed. */
  duplicate: boolean;
}

export interface ListResult {
  /** JSON array string of the persisted host records. */
  hosts: string;
}

export interface Spec extends TurboModule {
  /**
   * Persists a paired host. hints is a JSON array string of
   * {kind, addr, port}. A record with the same daemon public key is
   * refreshed and reported as a duplicate; the pin is never replaced.
   */
  add(daemonName: string, daemonPub: string, hints: string): Promise<AddResult>;

  /** Lists the persisted hosts as a JSON array string. */
  list(): Promise<ListResult>;

  /** Removes one host record by id. Rejects (code 0) when unknown. */
  remove(id: string): Promise<void>;

  /** Marks a host as recently connected. Rejects (code 0) when unknown. */
  touch(id: string): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyHosts');
