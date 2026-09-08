import React, { useEffect, useRef, useState } from 'react';
import { FlatList, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { RouteProp } from '@react-navigation/native';

import {
  SftpFilesBackend,
  isPlainName,
  viewEntries,
  type FileView,
  parseBreadcrumbs,
  parentPath,
  joinPath,
  type FileEntry,
  type FilesBackend,
} from '../../lib/files';
import { sftpBridge } from '../../lib/sftp';
import { SftpTransferBackend } from '../../lib/sftpTransfer';
import { openTransferSheet, useTransfers } from './TransferSheet';
import { filesTabCwd, setFilesTabCwd } from '../../lib/filesTabs';
import {
  activeTransfers,
  advanceTransfer,
  cancelTransfer as cancelRegisteredTransfer,
  registerTransfer,
  settleTransfer,
} from '../../lib/transfers';
import type { TransferBackend } from '../../lib/files';
import {
  pickFile,
  onPick,
  onSink,
  readChunk,
  writeChunk,
  release,
  discard,
  pickFolder,
  hasFolderAccess,
  findInFolder,
  createInFolder,
  type PickedFile,
} from '../../lib/fileIO';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';
import { log } from '../../lib/log';
import { Surface, TouchableRipple, useTheme } from 'react-native-paper';
import type { IconName } from '../../lib/icons';
import { Screen, type ScreenAction } from '../../components/Screen';
import { Empty, ErrorState, Loading, Notice } from '../../components/States';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';
import { Field } from '../../components/Form';
import { Icon } from '../../components/ui/icon';
import { Progress } from '../../components/ui/progress';
import { Text } from '../../components/ui/text';
import type { RootStackParamList } from '../../navigation/types';
import { useSettings } from '../../theme/SettingsProvider';
import { Breadcrumbs } from './Breadcrumbs';
import { FileListItem } from './FileListItem';
import { FilesToolbar } from './FilesToolbar';
import { entryKey, formatSize, numberedName } from './filePresentation';

// A file browser over SSH SFTP. Names are rendered byte-faithful.

type Phase = 'init' | 'connecting' | 'hostKey' | 'ready' | 'error';

interface HostKeyPrompt {
  algorithm: string;
  fingerprint: string;
  changed: boolean;
}

type TransferKind = 'upload' | 'download';

interface TransferState {
  kind: TransferKind;
  path: string;
  received: number;
  total: number;
  active: boolean;
  error?: string;
  done?: boolean;
  conflict?: boolean;
}

interface Prompt {
  kind: 'mkdir' | 'rename' | 'remove';
  target: string;
  text: string;
}

const PAGE_SIZE = 500;
// Bytes written per chunk on an upload (1 MiB minus an 8-byte frame offset).
const UPLOAD_CHUNK = 1024 * 1024 - 8;
const SFTP_POLL_MS = 150;
const SFTP_POLL_MAX = 60;

interface FilesScreenProps {
  /**
   * Renders the browser as a pane inside another screen instead of a route.
   *
   * An embedded browser has no chrome of its own: the host screen owns the
   * title bar and the tab strip, so the params come from the caller rather
   * than the navigation route.
   */
  embedded?: {
    hostId: string;
    /** Identifies the tab, so its directory survives a switch away. */
    tabId: string;
  };
}

export function FilesScreen({
  embedded,
}: FilesScreenProps = {}): React.ReactElement {
  const navigation =
    useNavigation<NativeStackNavigationProp<RootStackParamList>>();
  const route = useRoute<RouteProp<RootStackParamList, 'Files'>>();
  const hostIdParam = embedded?.hostId ?? route.params?.hostId ?? '';

  const [phase, setPhase] = useState<Phase>('init');
  const [backend, setBackend] = useState<FilesBackend | null>(null);
  const tabId = embedded?.tabId ?? '';
  const [cwd, setCwd] = useState(() => filesTabCwd(tabId));
  const [entries, setEntries] = useState<FileEntry[] | null>(null);
  // The query is per-screen: a search is about the folder in front of you,
  // not a preference. Hidden files and the sort order are settings, so they
  // are shared with the SSH browser and survive a restart.
  const [query, setQuery] = useState('');
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [hostKey, setHostKey] = useState<HostKeyPrompt | null>(null);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [prompt, setPrompt] = useState<Prompt | null>(null);
  // Validation for the name field, shown under it. Separate from `error`,
  // which is the screen-level notice sitting behind the open dialog.
  const [promptError, setPromptError] = useState('');
  const [transfer, setTransfer] = useState<TransferState | null>(null);
  // A download whose name is already taken in the destination folder. Held
  // until the user decides what to do with it.
  const [nameClash, setNameClash] = useState<{
    name: string;
    remotePath: string;
    existingUri: string;
    /** Remote size from the listing, or -1 when unknown. */
    size: number;
  } | null>(null);
  const { settings, update } = useSettings();

  const backendRef = useRef<FilesBackend | null>(null);
  const cwdRef = useRef(filesTabCwd(tabId));
  const xferRef = useRef<TransferBackend | null>(null);
  const nextOffsetRef = useRef(0);
  const activeXferRef = useRef<{
    id: string;
    uri: string;
    kind: TransferKind;
  } | null>(null);
  const pendingUploadRef = useRef<PickedFile | null>(null);
  // The remote path and size a download-folder pick is waiting on.
  const pendingDownloadRef = useRef<{ path: string; size: number } | null>(
    null,
  );
  // The remote name a folder pick was started for, so the download can carry
  // on once the folder comes back.
  const pendingFolderPickRef = useRef<{
    name: string;
    path: string;
    size: number;
  } | null>(null);
  // The download folder, readable from the mount-time event closures. Kept in
  // step with the stored setting, which is the source of truth across runs.
  const folderUriRef = useRef(settings.downloadFolderUri);
  const disposedRef = useRef(false);

  useEffect(() => {
    backendRef.current = backend;
  }, [backend]);

  useEffect(() => {
    folderUriRef.current = settings.downloadFolderUri;
  }, [settings.downloadFolderUri]);

  function setCur(p: string): void {
    cwdRef.current = p;
    setFilesTabCwd(tabId, p);
    setCwd(p);
  }

  // --- directory listing ---

  async function loadPage(
    path: string,
    offset: number,
    replace: boolean,
  ): Promise<void> {
    const b = backendRef.current;
    if (b === null) return;
    setLoading(true);
    setError('');
    try {
      const res = await b.list(path, offset, PAGE_SIZE);
      if (disposedRef.current) return;
      setEntries(prev =>
        replace ? res.entries : [...(prev ?? []), ...res.entries],
      );
      setMore(res.more);
      nextOffsetRef.current = offset + res.entries.length;
    } catch (e) {
      if (disposedRef.current) return;
      const err = toRemotlyError(e, 'unknown');
      log.error('files list failed', { path, code: err.code });
      setError(userFacingMessage(err));
      if (replace) setEntries(null);
    } finally {
      if (!disposedRef.current) setLoading(false);
    }
  }

  function loadDir(path: string): void {
    setCur(path);
    setEntries(null);
    setMore(false);
    nextOffsetRef.current = 0;
    void loadPage(path, 0, true);
  }

  function loadMore(): void {
    if (loading || !more) return;
    void loadPage(cwdRef.current, nextOffsetRef.current, false);
  }

  function navigate(path: string): void {
    setMenuFor(null);
    setPrompt(null);
    loadDir(path);
  }

  function goUp(): void {
    const parent = parentPath(cwdRef.current);
    if (parent !== null) navigate(parent);
  }

  // --- backend setup ---

  // Poll the SFTP session to a settled state: READY builds the backend, FAILED
  // errors out, HOST_KEY surfaces the approval prompt.
  async function pollSftp(hostId: string): Promise<void> {
    for (let i = 0; i < SFTP_POLL_MAX; i++) {
      if (disposedRef.current) return;
      const st = await sftpBridge.status(hostId);
      if (st.state === 'READY') {
        const b = new SftpFilesBackend(hostId, sftpBridge);
        backendRef.current = b;
        // SFTP moves files too; only resume and the whole-file hash differ,
        // which the capability table already records.
        xferRef.current = new SftpTransferBackend(hostId);
        setBackend(b);
        setPhase('ready');
        loadDir(cwdRef.current);
        return;
      }
      if (st.state === 'FAILED') {
        setError(st.message ?? 'SFTP connection failed');
        setPhase('error');
        return;
      }
      if (st.state === 'HOST_KEY' && st.hostKey !== undefined) {
        setHostKey({
          algorithm: st.hostKey.algorithm,
          fingerprint: st.hostKey.fingerprint,
          changed: st.changed === true,
        });
        setPhase('hostKey');
        return;
      }
      await new Promise(r => setTimeout(r, SFTP_POLL_MS));
    }
    if (disposedRef.current) return;
    setError('Timed out waiting for the SFTP session.');
    setPhase('error');
  }

  async function initSftp(hostId: string): Promise<void> {
    try {
      await sftpBridge.connect(hostId);
      await pollSftp(hostId);
    } catch (e) {
      if (disposedRef.current) return;
      setError(e instanceof Error ? e.message : 'SFTP connection failed');
      setPhase('error');
    }
  }

  function acceptSftpKey(): void {
    setHostKey(null);
    setPhase('connecting');
    const id = hostIdParam;
    void sftpBridge
      .hostKey(id, true)
      .then(() => pollSftp(id))
      .catch(e => {
        if (disposedRef.current) return;
        setError(e instanceof Error ? e.message : 'SFTP connection failed');
        setPhase('error');
      });
  }

  // --- metadata actions ---
  function beginMkdir(): void {
    setMenuFor(null);
    setPromptError('');
    setPrompt({ kind: 'mkdir', target: '', text: '' });
  }

  function beginRename(name: string): void {
    setMenuFor(null);
    setPromptError('');
    setPrompt({ kind: 'rename', target: name, text: name });
  }

  function beginRemove(name: string): void {
    setMenuFor(null);
    setPromptError('');
    setPrompt({ kind: 'remove', target: name, text: '' });
  }

  function cancelPrompt(): void {
    setPromptError('');
    setPrompt(null);
  }

  async function submitPrompt(): Promise<void> {
    const b = backendRef.current;
    const p = prompt;
    if (b === null || p === null) return;
    if (p.kind !== 'remove' && p.text.trim() === '') {
      cancelPrompt();
      return;
    }
    // The name is joined onto the current directory, so one carrying a
    // separator or a dot segment would act somewhere the user is not looking:
    // "a/b" nests a folder, "../x" moves the entry out of the directory. The
    // prompt stays open with the reason rather than doing it silently.
    if (p.kind !== 'remove' && !isPlainName(p.text)) {
      setPromptError('Use a name without a slash.');
      return;
    }
    setPromptError('');
    const name = p.kind === 'mkdir' ? p.text.trim() : p.target;
    setError('');
    try {
      if (p.kind === 'mkdir') {
        await b.mkdir(joinPath(cwdRef.current, name));
      } else if (p.kind === 'rename') {
        await b.rename(
          joinPath(cwdRef.current, p.target),
          joinPath(cwdRef.current, p.text.trim()),
        );
      } else {
        const entry = entries?.find(e => e.name === p.target) ?? null;
        const isDir = entry?.isDir ?? false;
        await b.remove(
          joinPath(cwdRef.current, p.target),
          isDir ? 'dir' : 'file',
        );
      }
      cancelPrompt();
      void loadDir(cwdRef.current);
    } catch (e) {
      const err = toRemotlyError(e, 'unknown');
      log.error('files op failed', {
        kind: p.kind,
        cwd: cwdRef.current,
        code: err.code,
      });
      setError(userFacingMessage(err));
      cancelPrompt();
    }
  }

  // --- transfers ---

  async function doUpload(
    picked: PickedFile,
    conflict: 'fail' | 'replace',
    resumeFrom = 0,
  ): Promise<void> {
    const xb = xferRef.current;
    if (xb === null) return;
    if (picked.size < 0) {
      setTransfer({
        kind: 'upload',
        path: picked.name,
        received: 0,
        total: -1,
        active: false,
        error: 'This file has an unknown size and cannot be uploaded.',
      });
      return;
    }
    pendingUploadRef.current = picked;
    const dest = joinPath(cwdRef.current, picked.name);
    setTransfer({
      kind: 'upload',
      path: picked.name,
      received: 0,
      total: picked.size,
      active: true,
    });
    let handleId: string | null = null;
    try {
      const handle = await xb.startUpload(
        dest,
        picked.size,
        conflict,
        undefined,
        resumeFrom,
      );
      handleId = handle.id;
      // The backend decides where it can actually continue from, which may be
      // less than asked for, so the local read follows the handle rather than
      // the request.
      const startAt = handle.startOffset ?? 0;
      const resumable = xb.capabilities.transferResume;
      registerTransfer(
        {
          id: handle.id,
          direction: 'upload',
          path: dest,
          name: picked.name,
          hostId: hostIdParam,
          total: picked.size,
          resumable,
        },
        () => void xb.cancel(handle.id).catch(() => undefined),
        from => void doUpload(picked, conflict, resumable ? from : 0),
      );
      activeXferRef.current = {
        id: handle.id,
        uri: picked.uri,
        kind: 'upload',
      };
      let offset = startAt;
      if (offset > 0) advanceTransfer(handle.id, offset);
      while (offset < picked.size) {
        if (activeXferRef.current?.id !== handle.id) break;
        const maxBytes = Math.min(UPLOAD_CHUNK, picked.size - offset);
        const { data, bytesRead } = await readChunk(
          picked.uri,
          offset,
          maxBytes,
        );
        if (bytesRead === 0) break;
        await xb.writeChunk(handle.id, offset, data);
        offset += bytesRead;
        advanceTransfer(handle.id, offset);
        setTransfer({
          kind: 'upload',
          path: picked.name,
          received: offset,
          total: picked.size,
          active: true,
        });
      }
      if (activeXferRef.current?.id !== handle.id) return;
      await xb.completeUpload(handle.id);
      settleTransfer(handle.id, 'done');
      setTransfer({
        kind: 'upload',
        path: picked.name,
        received: picked.size,
        total: picked.size,
        active: false,
        done: true,
      });
      void loadDir(cwdRef.current);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (handleId !== null) settleTransfer(handleId, 'error', msg);
      setTransfer({
        kind: 'upload',
        path: picked.name,
        received: 0,
        total: picked.size,
        active: false,
        error: msg,
        conflict: conflict === 'fail',
      });
    } finally {
      if (handleId !== null && activeXferRef.current?.id === handleId) {
        activeXferRef.current = null;
      }
      void release(picked.uri).catch(() => undefined);
    }
  }

  /**
   * Runs one download to a destination the caller already resolved.
   *
   * remoteSize is the size from the directory listing, or -1 when it is not
   * known. It seeds the progress total: the SFTP backend reports the size only
   * once the transfer finishes, so without it the bar has no denominator and
   * renders in its static indeterminate form for the whole download, which
   * reads as frozen.
   */
  async function doDownload(
    picked: PickedFile,
    path: string,
    resumeFrom = 0,
    remoteSize = -1,
  ): Promise<void> {
    const xb = xferRef.current;
    if (xb === null) return;
    setTransfer({
      kind: 'download',
      path: picked.name,
      received: resumeFrom,
      total: remoteSize,
      active: true,
    });
    // The fast path: the backend writes the file itself, so no file bytes
    // cross into JS. Falls through to the chunked path for a backend that
    // cannot reach the destination on its own.
    const direct = xb.startDownloadToUri;
    if (direct !== undefined) {
      let id: string | null = null;
      try {
        const handle = await direct.call(
          xb,
          path,
          picked.uri,
          received => {
            if (id === null) return;
            // Recorded unconditionally: the transfer runs in native and keeps
            // going after this screen is gone, and the app-wide sheet reads
            // this. Only the local banner is gated on the screen still owning
            // the transfer.
            advanceTransfer(id, received);
            if (activeXferRef.current?.id !== id) return;
            setTransfer(t => {
              if (t === null || t.kind !== 'download' || !t.active) return t;
              return { ...t, received };
            });
          },
          totalBytes => {
            if (id !== null) {
              advanceTransfer(id, totalBytes);
              settleTransfer(id, 'done');
            }
            // Only this screen's banner is conditional. The transfer itself
            // is settled above whether or not anyone is watching.
            const owned = id !== null && activeXferRef.current?.id === id;
            if (owned) activeXferRef.current = null;
            if (owned) {
              setTransfer({
                kind: 'download',
                path: picked.name,
                received: totalBytes,
                total: totalBytes,
                active: false,
                done: true,
              });
            }
            void release(picked.uri).catch(() => undefined);
          },
          msg => {
            if (id !== null) settleTransfer(id, 'error', msg);
            const owned = id !== null && activeXferRef.current?.id === id;
            if (owned) activeXferRef.current = null;
            if (owned) {
              setTransfer({
                kind: 'download',
                path: picked.name,
                received: 0,
                total: -1,
                active: false,
                error: msg,
              });
            }
            // Kept only when Resume can continue from what is on disk.
            // Otherwise the partial file goes: it carries the name the user
            // chose and would pass for a complete download.
            if (xb.capabilities.transferResume) {
              void release(picked.uri).catch(() => undefined);
            } else {
              void discard(picked.uri).catch(() => undefined);
            }
          },
          resumeFrom,
        );
        id = handle.id;
        // Registered so the transfer is visible app-wide and, more to the
        // point, so the unmount cleanup can see it is still running. An
        // unregistered download read as "nothing active" and the SFTP
        // connection was closed under it on navigating away.
        const resumable = xb.capabilities.transferResume;
        registerTransfer(
          {
            id: handle.id,
            direction: 'download',
            path,
            name: picked.name,
            hostId: hostIdParam,
            total: handle.size > 0 ? handle.size : remoteSize,
            resumable,
          },
          () => {
            void xb.cancel(handle.id).catch(() => undefined);
            // Cancelled from the sheet, possibly from another screen. The
            // partial file is dropped unless Resume can continue from it.
            if (!resumable) void discard(picked.uri).catch(() => undefined);
          },
          from =>
            void doDownload(picked, path, resumable ? from : 0, remoteSize),
        );
        if (resumeFrom > 0) advanceTransfer(handle.id, resumeFrom);
        activeXferRef.current = {
          id: handle.id,
          uri: picked.uri,
          kind: 'download',
        };
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        activeXferRef.current = null;
        setTransfer({
          kind: 'download',
          path: picked.name,
          received: 0,
          total: -1,
          active: false,
          error: msg,
        });
        // The transfer never started, so the picker's empty file is all that
        // exists. Leaving it behind looks like a download that produced a
        // zero-byte result.
        void discard(picked.uri).catch(() => undefined);
      }
      return;
    }

    // Chunks are appended to one stream, so they must be written one at a
    // time and in order. Firing each write off without waiting lets the next
    // one start mid-append, and the two interleave into a corrupt file.
    let writes: Promise<void> = Promise.resolve();
    let writeFailed: string | null = null;
    // Counted here rather than read back off the banner's state. The banner
    // belongs to this screen and stops updating once the user navigates away,
    // and taking the running total from it meant a backgrounded download
    // recorded no progress and, worse, wrote nothing.
    let received = resumeFrom;

    try {
      const handle = await xb.startDownload(
        path,
        (_offset, bytes) => {
          // Deliberately not gated on this screen still owning the transfer.
          // The download continues in the background, so every chunk has to
          // reach the file; dropping them silently truncated the result.
          writes = writes.then(async () => {
            if (writeFailed !== null) return;
            try {
              await writeChunk(picked.uri, bytes);
            } catch (err) {
              writeFailed = err instanceof Error ? err.message : String(err);
              log.error('download sink write failed', { message: writeFailed });
            }
          });
          received += bytes.length;
          advanceTransfer(handle.id, received);
          // Only the local banner is conditional: it is this screen's view of
          // a transfer that no longer belongs to it.
          if (activeXferRef.current?.id !== handle.id) return;
          setTransfer(t => {
            if (t === null || t.kind !== 'download' || !t.active) return t;
            return {
              ...t,
              received,
              total: handle.size > 0 ? handle.size : t.total,
            };
          });
        },
        totalBytes => {
          const owned = activeXferRef.current?.id === handle.id;
          if (owned) activeXferRef.current = null;
          // The last chunks may still be in flight. Releasing the file before
          // they land truncates it, and reporting success would be a lie.
          void writes.then(() => {
            if (writeFailed !== null) {
              settleTransfer(handle.id, 'error', writeFailed);
              if (owned) {
                setTransfer({
                  kind: 'download',
                  path: picked.name,
                  received: 0,
                  total: -1,
                  active: false,
                  error: writeFailed,
                });
              }
            } else {
              advanceTransfer(handle.id, totalBytes);
              settleTransfer(handle.id, 'done');
              if (owned) {
                setTransfer({
                  kind: 'download',
                  path: picked.name,
                  received: totalBytes,
                  total: totalBytes,
                  active: false,
                  done: true,
                });
              }
            }
            void release(picked.uri).catch(() => undefined);
          });
        },
        msg => {
          const owned = activeXferRef.current?.id === handle.id;
          if (owned) activeXferRef.current = null;
          settleTransfer(handle.id, 'error', msg);
          if (owned) {
            setTransfer({
              kind: 'download',
              path: picked.name,
              received: 0,
              total: -1,
              active: false,
              error: msg,
            });
          }
          // Waits for pending writes so the stream is not closed underneath
          // one, then drops the partial file: this path cannot resume, and
          // what is on disk carries the name the user chose.
          void writes.then(() => discard(picked.uri).catch(() => undefined));
        },
      );
      registerTransfer(
        {
          id: handle.id,
          direction: 'download',
          path,
          name: picked.name,
          hostId: hostIdParam,
          total: handle.size > 0 ? handle.size : remoteSize,
          // This path appends through JS and cannot seek the destination, so
          // picking it back up starts over. The sheet says Retry rather than
          // Resume, which is what actually happens.
          resumable: false,
        },
        () => {
          void xb.cancel(handle.id).catch(() => undefined);
          // Waits on the pending writes for the same reason the error path
          // does: discarding while one is in flight closes the stream under
          // it. Cancelling from the sheet reaches here from another screen.
          void writes.then(() => discard(picked.uri).catch(() => undefined));
        },
        () => void doDownload(picked, path, 0, remoteSize),
      );
      activeXferRef.current = {
        id: handle.id,
        uri: picked.uri,
        kind: 'download',
      };
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      activeXferRef.current = null;
      setTransfer({
        kind: 'download',
        path: picked.name,
        received: 0,
        total: -1,
        active: false,
        error: msg,
      });
      // This path cannot resume, so nothing on disk is worth keeping.
      void discard(picked.uri).catch(() => undefined);
    }
  }

  function startUpload(): void {
    if (xferRef.current === null) {
      setTransfer({
        kind: 'upload',
        path: '',
        received: 0,
        total: -1,
        active: false,
        error: 'No transfer backend for this host.',
      });
      return;
    }
    pickFile('upload').catch(e => {
      log.error('upload pick failed', {
        message: userFacingMessage(toRemotlyError(e, 'unknown')),
      });
    });
  }

  function startDownload(name: string, path: string, size: number): void {
    if (xferRef.current === null) {
      setTransfer({
        kind: 'download',
        path: name,
        received: 0,
        total: -1,
        active: false,
        error: 'No transfer backend for this host.',
      });
      return;
    }
    void beginDownload(name, path, size);
  }

  /**
   * Resolves where a download should land before any bytes move.
   *
   * With a folder the app holds, an existing name is found first and the user
   * is asked. Without one the folder is requested; the create-document picker
   * is not used as a fallback, because it resolves a collision by renaming and
   * never reports that it did.
   */
  async function beginDownload(
    name: string,
    path: string,
    size: number,
  ): Promise<void> {
    // Read through the ref, not the captured value. The event subscription
    // that calls this is registered once on mount, so its closure holds the
    // settings as they were then: the folder just granted was saved and never
    // seen, and every download asked for it again.
    const folder = folderUriRef.current;
    const usable = folder !== '' && (await hasFolderAccess(folder));
    if (!usable) {
      pendingFolderPickRef.current = { name, path, size };
      pickFolder().catch(e => {
        pendingFolderPickRef.current = null;
        log.error('folder pick failed', {
          message: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
      });
      return;
    }

    try {
      const existing = await findInFolder(folder, name);
      if (existing !== null) {
        setNameClash({
          name,
          remotePath: path,
          existingUri: existing,
          size,
        });
        return;
      }
      const uri = await createInFolder(folder, name);
      void doDownload({ uri, name, size: -1, mode: 'download' }, path, 0, size);
    } catch (e) {
      setTransfer({
        kind: 'download',
        path: name,
        received: 0,
        total: -1,
        active: false,
        error: userFacingMessage(toRemotlyError(e, 'unknown')),
      });
    }
  }

  /** The first name free in the folder, asking the provider each time. */
  async function freeNameIn(folder: string, name: string): Promise<string> {
    for (let i = 1; i <= 999; i += 1) {
      const candidate = numberedName(name, i);
      if ((await findInFolder(folder, candidate)) === null) return candidate;
    }
    return numberedName(name, Date.now());
  }

  /** Overwrites the existing file, keeping the name the user asked for. */
  function resolveReplace(): void {
    const c = nameClash;
    setNameClash(null);
    if (c === null) return;
    // The document already exists, so it is written in place rather than
    // created again. Opening it truncates, which is the replace.
    void doDownload(
      { uri: c.existingUri, name: c.name, size: -1, mode: 'download' },
      c.remotePath,
      0,
      c.size,
    );
  }

  /** Saves alongside the existing file under a non-colliding name. */
  function resolveKeepBoth(): void {
    const c = nameClash;
    const folder = folderUriRef.current;
    setNameClash(null);
    if (c === null) return;
    void (async () => {
      try {
        // The provider owns the directory, so a free name is found by asking
        // it rather than from the remote listing.
        const candidate = await freeNameIn(folder, c.name);
        const uri = await createInFolder(folder, candidate);
        void doDownload(
          { uri, name: candidate, size: -1, mode: 'download' },
          c.remotePath,
          0,
          c.size,
        );
      } catch (e) {
        setTransfer({
          kind: 'download',
          path: c.name,
          received: 0,
          total: -1,
          active: false,
          error: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
      }
    })();
  }

  function retryReplace(): void {
    const picked = pendingUploadRef.current;
    if (picked === null) return;
    void doUpload(picked, 'replace');
  }

  function cancelTransfer(): void {
    const ax = activeXferRef.current;
    if (ax === null) return;
    // Routed through the registry so the cancel runs the same callback the
    // sheet does. That one waits on any pending write before dropping the
    // partial file; discarding here as well closed the stream twice.
    cancelRegisteredTransfer(ax.id);
    activeXferRef.current = null;
    setTransfer(t =>
      t !== null && t.active ? { ...t, active: false, error: 'Cancelled.' } : t,
    );
  }

  function dismissTransfer(): void {
    setTransfer(null);
    pendingUploadRef.current = null;
    pendingDownloadRef.current = null;
  }

  // --- lifecycle ---

  useEffect(() => {
    log.info('files screen mounted', { host: hostIdParam.slice(0, 8) });
    disposedRef.current = false;
    if (hostIdParam === '') {
      setPhase('error');
      setError('No host to open. Open the files browser from a host.');
    } else {
      setPhase('connecting');
      void initSftp(hostIdParam);
    }

    const unsubs = [
      onPick(f => {
        // The terminal's image paste shares this event; only answer our own.
        if (f.mode !== 'upload') return;
        void doUpload(f, 'fail');
      }),
      onSink(f => {
        // A folder pick answers on this event too, with no name. Storing it
        // and continuing is what makes the ask happen once rather than per
        // download.
        const wanted = pendingFolderPickRef.current;
        if (wanted !== null) {
          pendingFolderPickRef.current = null;
          // Recorded before the save resolves, so the retry below sees it
          // whatever the store does.
          folderUriRef.current = f.uri;
          void update({ downloadFolderUri: f.uri }).catch(() => undefined);
          void beginDownload(wanted.name, wanted.path, wanted.size);
          return;
        }
        const p = pendingDownloadRef.current;
        pendingDownloadRef.current = null;
        if (p !== null) void doDownload(f, p.path, 0, p.size);
      }),
    ];

    return () => {
      disposedRef.current = true;
      unsubs.forEach(u => u());
      // Only this screen's claim on the transfer is dropped. The transfer
      // itself keeps running and reporting into the app-wide store.
      activeXferRef.current = null;
      // SFTP holds a live connection per host. It is kept open while a
      // transfer is still using it, and closed otherwise.
      if (activeTransfers().length === 0)
        void sftpBridge.close(hostIdParam).catch(() => undefined);
    };
    // The handlers read refs only, so the first-render closures stay valid.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function closePage(): void {
    navigation.goBack();
  }

  // --- render ---

  const crumbs = parseBreadcrumbs(cwd, '/');

  // Stable callbacks, so the memoized rows are not invalidated on every list
  // render.
  const openEntry = React.useCallback(
    (entry: FileEntry) => {
      if (entry.isDir) navigate(joinPath(cwdRef.current, entry.name));
      else setMenuFor(entry.name);
    },
    // navigate is stable for the life of the screen.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );
  const menuForEntry = React.useCallback(
    (entry: FileEntry) => setMenuFor(entry.name),
    [],
  );
  // The persisted preferences plus this screen's search box.
  const view: FileView = React.useMemo(
    () => ({
      sortKey: settings.filesSortKey,
      direction: settings.filesSortDirection,
      showHidden: settings.filesShowHidden,
      query,
    }),
    [
      settings.filesSortKey,
      settings.filesSortDirection,
      settings.filesShowHidden,
      query,
    ],
  );

  const setView = React.useCallback(
    (next: FileView) => {
      setQuery(next.query);
      if (
        next.sortKey !== view.sortKey ||
        next.direction !== view.direction ||
        next.showHidden !== view.showHidden
      ) {
        void update({
          filesSortKey: next.sortKey,
          filesSortDirection: next.direction,
          filesShowHidden: next.showHidden,
        });
      }
    },
    [update, view],
  );

  // Entries stay raw: the filter is display only, and every operation still
  // addresses the entry by its real name.
  const shownEntries = React.useMemo(
    () => viewEntries(entries ?? [], view),
    [entries, view],
  );

  const renderEntry = React.useCallback(
    ({ item }: { item: FileEntry }) => (
      <FileListItem entry={item} onOpen={openEntry} onMenu={menuForEntry} />
    ),
    [openEntry, menuForEntry],
  );
  const canTransfer = xferRef.current !== null;
  const runningCount = useTransfers().filter(t => t.phase === 'active').length;
  const openTransfers = React.useCallback(() => openTransferSheet(), []);
  const { colors } = useTheme();

  const actions: ScreenAction[] = [
    {
      key: 'mkdir',
      icon: 'plus',
      title: 'New folder',
      onPress: beginMkdir,
      disabled: phase !== 'ready',
    },
    ...(canTransfer
      ? [
          {
            key: 'upload',
            icon: 'arrow-up' as const,
            title: 'Upload a file',
            onPress: startUpload,
            disabled: phase !== 'ready',
          },
        ]
      : []),
    {
      key: 'transfers',
      icon: 'arrow-up-down' as const,
      // The count is in the name so it is announced, not only drawn.
      title:
        runningCount > 0 ? `Transfers, ${runningCount} running` : 'Transfers',
      onPress: openTransfers,
    },
  ];

  return (
    <Screen
      title="Files"
      bare={embedded !== undefined}
      {...(embedded === undefined ? { onBack: closePage } : {})}
      actions={actions}
    >
      {/* The transfer sheet is mounted once, in RootNavigator. */}
      {phase === 'ready' ? (
        <Breadcrumbs crumbs={crumbs} path={cwd} onNavigate={navigate} />
      ) : null}

      {phase === 'connecting' || phase === 'init' ? (
        <Loading label="Opening files" />
      ) : null}

      {phase === 'error' ? (
        <ErrorState
          title="Could not open files"
          message={error || 'The connection could not be opened.'}
          onRetry={closePage}
          retryLabel="Close"
        />
      ) : null}

      {phase === 'hostKey' && hostKey !== null ? (
        <View
          style={{
            flex: 1,
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            padding: 24,
          }}
        >
          <Text variant="title" style={{ textAlign: 'center' }}>
            {hostKey.changed ? 'Host key changed' : 'New host key'}
          </Text>
          <Text variant="muted" style={{ textAlign: 'center' }}>
            {hostKey.changed
              ? 'A different key than the one you approved was presented. This may be a sign of tampering. Only continue if you expect the key to change.'
              : 'This server presented a host key you have not seen before. Approving it stores the key for this host.'}
          </Text>
          <Text variant="code" style={{ textAlign: 'center' }}>
            {hostKey.algorithm}
          </Text>
          <Text variant="code" style={{ textAlign: 'center' }}>
            {hostKey.fingerprint}
          </Text>
          <View style={{ flexDirection: 'row', gap: 8, paddingVertical: 8 }}>
            <Button variant="outline" onPress={closePage}>
              Reject
            </Button>
            <Button onPress={acceptSftpKey}>Trust and continue</Button>
          </View>
        </View>
      ) : null}

      {phase === 'ready' && error !== '' ? (
        <Notice tone="danger" message={error} />
      ) : null}

      {phase === 'ready' ? (
        <FilesToolbar
          view={view}
          shown={shownEntries.length}
          loaded={entries?.length ?? 0}
          onChange={setView}
        />
      ) : null}

      {phase === 'ready' ? (
        <FlatList
          data={shownEntries}
          keyExtractor={e => entryKey(cwd, e)}
          style={{ flex: 1 }}
          contentContainerStyle={{ paddingBottom: 48 }}
          ListHeaderComponent={
            parentPath(cwd) !== null ? <UpRow onPress={goUp} /> : undefined
          }
          ListEmptyComponent={
            entries !== null && shownEntries.length === 0 ? (
              entries.length > 0 ? (
                <Empty
                  icon="magnify"
                  title="Nothing matches"
                  message={
                    more
                      ? 'Only the entries loaded so far were searched. Scroll to load the rest of this folder.'
                      : 'No entry here matches the search and hidden-file settings.'
                  }
                />
              ) : (
                <Empty
                  icon="folder-open"
                  title="This folder is empty"
                  message={
                    canTransfer
                      ? 'Upload a file to get started.'
                      : 'This host supports browsing and metadata only.'
                  }
                />
              )
            ) : undefined
          }
          ListFooterComponent={
            loading && (entries === null || more) ? (
              <View
                style={{
                  alignItems: 'center',
                  paddingHorizontal: 32,
                  paddingVertical: 16,
                }}
              >
                <Progress
                  label="Loading more entries"
                  style={{ width: '50%' }}
                />
              </View>
            ) : undefined
          }
          onEndReached={loadMore}
          onEndReachedThreshold={0.2}
          renderItem={renderEntry}
        />
      ) : null}

      {/* Transfer progress / result. */}
      {transfer !== null ? (
        <Surface
          elevation={0}
          style={{
            gap: 8,
            borderTopWidth: 1,
            borderTopColor: colors.outlineVariant as string,
            backgroundColor: colors.surfaceContainerLow as string,
            padding: 16,
          }}
        >
          <Text variant="title" numberOfLines={1}>
            {transfer.kind === 'upload' ? 'Uploading' : 'Downloading'}{' '}
            {transfer.path}
          </Text>
          {transfer.active ? (
            <Progress
              label={
                transfer.kind === 'upload'
                  ? 'Upload progress'
                  : 'Download progress'
              }
              {...(transfer.total > 0
                ? { value: transfer.received / transfer.total }
                : {})}
            />
          ) : null}
          <Text variant="caption">
            {transfer.active
              ? transfer.total > 0
                ? `${formatSize(transfer.received)} of ${formatSize(
                    transfer.total,
                  )}`
                : formatSize(transfer.received)
              : transfer.done
              ? 'Complete.'
              : transfer.error ?? ''}
          </Text>
          <View
            style={{
              flexDirection: 'row',
              justifyContent: 'flex-end',
              gap: 8,
            }}
          >
            {transfer.active ? (
              <Button
                variant="outline"
                size="sm"
                onPress={cancelTransfer}
                accessibilityLabel="Cancel transfer"
              >
                Cancel
              </Button>
            ) : null}
            {transfer.conflict === true ? (
              <Button
                size="sm"
                onPress={retryReplace}
                accessibilityLabel="Replace the existing file and retry"
              >
                Replace and retry
              </Button>
            ) : null}
            {!transfer.active || transfer.conflict === true ? (
              <Button variant="ghost" size="sm" onPress={dismissTransfer}>
                Dismiss
              </Button>
            ) : null}
          </View>
        </Surface>
      ) : null}

      {phase === 'ready' ? (
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderTopWidth: 1,
            borderTopColor: colors.outlineVariant as string,
            paddingHorizontal: 8,
            paddingVertical: 4,
          }}
        >
          <Button
            variant="ghost"
            size="sm"
            icon="refresh"
            onPress={() => loadDir(cwdRef.current)}
            accessibilityLabel="Refresh this folder"
          >
            Refresh
          </Button>
          <Text variant="caption" style={{ marginLeft: 8, flexShrink: 1 }}>
            {canTransfer
              ? 'Transfers: resume available, no integrity check'
              : 'Browsing and metadata only.'}
          </Text>
        </View>
      ) : null}

      {/* A download whose name is already taken in the destination folder.
          The choice is the user's: overwriting silently is destructive, and
          renaming silently is what the system picker does and why a
          collision used to pass unnoticed. */}
      <Sheet open={nameClash !== null} onClose={() => setNameClash(null)}>
        <SheetHeader>
          <SheetTitle>{nameClash?.name ?? ''} already exists</SheetTitle>
        </SheetHeader>
        <SheetContent style={{ gap: 8, paddingBottom: 24 }}>
          <Text
            variant="muted"
            style={{ paddingHorizontal: 4, paddingBottom: 4 }}
          >
            A file with this name is already in your download folder.
          </Text>
          <MenuRow
            icon="content-copy"
            label="Keep both"
            onPress={resolveKeepBoth}
          />
          <MenuRow icon="refresh" label="Replace" onPress={resolveReplace} />
        </SheetContent>
      </Sheet>

      {/* Entry action menu. */}
      <Sheet
        open={menuFor !== null && prompt === null}
        onClose={() => setMenuFor(null)}
      >
        <SheetHeader>
          <SheetTitle>{menuFor ?? ''}</SheetTitle>
        </SheetHeader>
        <SheetContent style={{ gap: 4, paddingBottom: 24 }}>
          {canTransfer ? (
            <MenuRow
              icon="file-download"
              label="Download"
              onPress={() => {
                const name = menuFor;
                if (name === null) return;
                setMenuFor(null);
                const entry = entries?.find(e => e.name === name);
                startDownload(
                  name,
                  joinPath(cwdRef.current, name),
                  entry !== undefined && entry.size > 0 ? entry.size : -1,
                );
              }}
            />
          ) : null}
          <MenuRow
            icon="pencil"
            label="Rename"
            onPress={() => {
              if (menuFor !== null) beginRename(menuFor);
            }}
          />
          <MenuRow
            icon="delete"
            label="Delete"
            destructive
            onPress={() => {
              if (menuFor !== null) beginRemove(menuFor);
            }}
          />
        </SheetContent>
      </Sheet>

      {/* Text prompt: new folder, rename, or a delete confirm. */}
      <Dialog open={prompt !== null} onClose={cancelPrompt}>
        <DialogHeader>
          <DialogTitle>
            {prompt?.kind === 'mkdir'
              ? 'New folder name'
              : prompt?.kind === 'rename'
              ? `Rename ${prompt.target}`
              : `Delete ${prompt?.target}?`}
          </DialogTitle>
        </DialogHeader>
        <DialogContent>
          {prompt?.kind !== 'remove' ? (
            <Field
              label={prompt?.kind === 'mkdir' ? 'Folder name' : 'New name'}
              value={prompt?.text ?? ''}
              onChangeText={t => {
                setPromptError('');
                setPrompt(p => (p !== null ? { ...p, text: t } : p));
              }}
              autoCapitalize="none"
              autoCorrect={false}
              {...(promptError === '' ? {} : { error: promptError })}
            />
          ) : (
            <Text variant="muted">
              This cannot be undone. Directories are removed only when empty;
              the server reports a non-empty target as an error.
            </Text>
          )}
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" onPress={cancelPrompt}>
            Cancel
          </Button>
          <Button
            variant={prompt?.kind === 'remove' ? 'destructive' : 'default'}
            onPress={() => void submitPrompt()}
            accessibilityLabel={
              prompt?.kind === 'remove' ? 'Delete' : 'Confirm'
            }
          >
            {prompt?.kind === 'remove' ? 'Delete' : 'OK'}
          </Button>
        </DialogFooter>
      </Dialog>
    </Screen>
  );
}

// A row in one of this screen's action sheets.
function MenuRow({
  icon,
  label,
  destructive = false,
  onPress,
}: {
  icon: IconName;
  label: string;
  destructive?: boolean;
  onPress: () => void;
}): React.ReactElement {
  const { colors } = useTheme();
  return (
    <TouchableRipple
      role="button"
      onPress={onPress}
      style={{
        height: 56,
        borderRadius: 16,
        paddingHorizontal: 16,
        justifyContent: 'center',
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 16 }}>
        <View
          style={{
            height: 40,
            width: 40,
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 999,
            backgroundColor: (destructive
              ? colors.errorContainer
              : colors.secondaryContainer) as string,
          }}
        >
          <Icon
            name={icon}
            size={20}
            color={
              (destructive
                ? colors.error
                : colors.onSecondaryContainer) as string
            }
          />
        </View>
        <Text
          variant="body"
          style={{
            flex: 1,
            fontWeight: '500',
            ...(destructive ? { color: colors.error as string } : {}),
          }}
        >
          {label}
        </Text>
      </View>
    </TouchableRipple>
  );
}

// The parent-directory row. At module scope so FlatList's header type is
// stable across renders.
function UpRow({ onPress }: { onPress: () => void }): React.ReactElement {
  const { colors } = useTheme();
  return (
    <Button
      variant="ghost"
      icon="arrow-up"
      style={{ borderRadius: 0 }}
      contentStyle={{ height: 56, justifyContent: 'flex-start' }}
      labelStyle={{ color: colors.onSurfaceVariant as string }}
      accessibilityLabel="Up one level"
      onPress={onPress}
    >
      ..
    </Button>
  );
}
