import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for the file I/O bridge (remotly.file.*). The app never holds a local
// path: pick launches the system picker and the chosen content URI arrives via
// onPicked (upload) or onSink (download); readChunk/writeChunk move chunks
// between that URI and the transfer engine through the ContentResolver. Chunks
// are standard base64.

export interface FilePickPayload {
  /** The content URI, or "" when the picker was cancelled. */
  uri: string;
  /** The display name, when the provider reports one. */
  name: string;
  /** The size in bytes, or -1 when the provider does not report it. */
  size: number;
}

export interface FileReadResult {
  /** Standard base64 of the chunk read; "" at end of file. */
  data: string;
  /** The number of bytes read. */
  bytesRead: number;
}

export interface FileWriteResult {
  bytesWritten: number;
}

export interface Spec extends TurboModule {
  /**
   * Launches the system picker. mode is "upload" (open an existing document)
   * or "download" (create a destination). Resolves once the picker is
   * launched; the choice arrives through onPicked or onSink.
   */
  pick(mode: string, name: string): Promise<void>;

  /** Reads up to maxBytes from uri at offset. */
  readChunk(
    uri: string,
    offset: number,
    maxBytes: number,
  ): Promise<FileReadResult>;

  /** Appends data (standard base64) to the document at uri. */
  writeChunk(uri: string, data: string): Promise<FileWriteResult>;

  /** Drops the cached streams for uri. */
  release(uri: string): Promise<void>;

  /**
   * Deletes a destination document this app created but did not fill.
   *
   * The download picker creates the file before the transfer starts, so a
   * failure partway leaves a truncated file under the name the user chose.
   * Callers use this instead of release when the transfer did not complete
   * and cannot be resumed.
   */
  discard(uri: string): Promise<void>;

  /**
   * Launches the folder picker and persists access to the choice.
   *
   * The result arrives on onSink with an empty name, like the other picks.
   * Granting a folder once is what lets the app see a name collision before
   * it creates anything: the create-document picker silently renames instead,
   * so the user never gets to decide.
   */
  pickFolder(): Promise<void>;

  /**
   * Reports whether a folder grant is still usable.
   *
   * A persisted permission can be revoked from system settings or lost when
   * the folder is deleted, so a stored one is checked rather than trusted.
   */
  hasFolderAccess(treeUri: string): Promise<boolean>;

  /** The URI of `name` inside the folder, or "" when nothing has that name. */
  findInFolder(treeUri: string, name: string): Promise<string>;

  /** Creates `name` in the folder and returns the new document's URI. */
  createInFolder(treeUri: string, name: string): Promise<string>;

  /**
   * Declares whether any transfer is currently moving bytes.
   *
   * While true a foreground service runs, which is what stops Android from
   * suspending the process once the app leaves the foreground. Both backends
   * report through here: an SFTP transfer runs on a native thread and a daemon
   * transfer runs over the JS transport, but neither survives a backgrounded
   * process without it.
   */
  setTransfersActive(active: boolean): Promise<void>;

  /** The chosen document for an upload pick. */
  readonly onPicked: EventEmitter<FilePickPayload>;
  /** The created destination for a download pick. */
  readonly onSink: EventEmitter<FilePickPayload>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyFileIO');
