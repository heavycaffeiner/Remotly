import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { EventEmitter } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for the SFTP browser bridge (remotly.sftp.*). One live connection per
// host id (the SftpBridge invariant). The host-key prompt is poll/answer:
// status surfaces the new/changed key state and hostKey answers it. Entries
// and host keys travel as JSON strings so the bridge carries no nested models.
// Paths and names are passed through untouched (byte-faithful); nothing here
// normalizes NFC/NFD, trims, or case-folds.

export interface SftpStatusResult {
  // CONNECTING | HOST_KEY | READY | FAILED, or NONE when no session.
  state: string;
  /** The presented host key as a JSON string, when a prompt is pending. */
  hostKey?: string;
  /** True when the pending prompt is a changed (not first-use) key. */
  changed?: boolean;
  /** Failure code, when state is FAILED. */
  code?: string;
  /** Failure message, when state is FAILED. */
  message?: string;
}

export interface SftpListResult {
  /** JSON array string of the directory entries. */
  entries: string;
}

export interface SftpStatResult {
  /** One entry as a JSON string. */
  entry: string;
}

/**
 * One slice of a download, or its outcome.
 *
 * Bytes travel as standard base64 because the bridge carries no binary type.
 * A chunk has `data`; the final event has `done` or `error` instead, so a
 * transfer always terminates exactly once.
 */
export type SftpTransferEvent = {
  /** The transfer this belongs to. */
  id: string;
  /** Byte offset of this chunk within the file. */
  offset: number;
  /** Standard base64 of the chunk, absent on the final event. */
  data?: string;
  /** Set on success; carries the total bytes transferred. */
  done?: number;
  /** Set on failure; carries the reason. */
  error?: string;
};

export interface Spec extends TurboModule {
  /**
   * Starts the SFTP connection for a stored host and returns immediately.
   * Poll status for the outcome; a new or changed host key parks in the
   * HOST_KEY state until hostKey is called.
   */
  connect(hostId: string): Promise<void>;

  /** Snapshot of the host's SFTP connection state. */
  status(hostId: string): Promise<SftpStatusResult>;

  /** Answers a host-key prompt. Accepting records the presented key. */
  hostKey(hostId: string, accept: boolean): Promise<void>;

  /** Lists a directory. Resolves with the entries as a JSON array string. */
  list(hostId: string, path: string): Promise<SftpListResult>;

  /** Stats one path (lstat: a symlink reports as a symlink). */
  stat(hostId: string, path: string): Promise<SftpStatResult>;

  /** Creates a directory. */
  mkdir(hostId: string, path: string): Promise<void>;

  /** Renames a file or directory. */
  rename(hostId: string, from: string, to: string): Promise<void>;

  /** Removes a file (isDir false) or an empty directory (isDir true). */
  remove(hostId: string, path: string, isDir: boolean): Promise<void>;

  /**
   * Opens the file for writing and returns a transfer id.
   *
   * `conflict` is "replace" to truncate an existing file or "fail" to refuse
   * one. The caller then writes chunks in order and calls completeUpload.
   */
  startUpload(hostId: string, path: string, conflict: string): Promise<string>;

  /**
   * Opens a file for writing at its current end and returns a transfer id.
   *
   * Used to pick an interrupted upload back up: what already reached the
   * server is kept and writing continues after it. The resolved offset to
   * continue from is reported through onTransfer as the first progress event.
   */
  startUploadResume(hostId: string, path: string): Promise<string>;

  /**
   * Writes one chunk at `offset` and resolves with the bytes written.
   *
   * `data` is standard base64. Chunks must be written in order; a gap is
   * rejected rather than padded, so a dropped chunk cannot corrupt the file.
   */
  writeChunk(id: string, offset: number, data: string): Promise<number>;

  /** Flushes and closes the upload. The file is incomplete until this. */
  completeUpload(id: string): Promise<void>;

  /**
   * Starts reading a file and returns a transfer id.
   *
   * Chunks arrive on onTransfer in offset order, terminated by exactly one
   * event carrying `done` or `error`.
   */
  startDownload(hostId: string, path: string): Promise<string>;

  /**
   * Reads a file straight into a content URI and returns a transfer id.
   *
   * The bytes never enter JS. onTransfer still reports progress and the single
   * terminal event, but carries no `data`, so a download costs one event per
   * chunk instead of a base64 round trip and a second bridge call to write it.
   */
  startDownloadToUri(
    hostId: string,
    path: string,
    uri: string,
    /** Byte offset to seek to before reading. Zero starts from the beginning. */
    resumeFrom: number,
  ): Promise<string>;

  /** Cancels a transfer in either direction. A no-op once it has settled. */
  cancelTransfer(id: string): Promise<void>;

  /** Download chunks and terminal events for every active transfer. */
  readonly onTransfer: EventEmitter<SftpTransferEvent>;

  /** Closes the host's SFTP connection. A no-op when none is open. */
  close(hostId: string): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlySftp');
