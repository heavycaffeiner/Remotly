import React, { useEffect, useRef, useState } from 'react';
import { FlatList, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import type { RouteProp } from '@react-navigation/native';

import { getHosts } from '../../lib/hosts';
import { getTransport } from '../../lib/transport';
import { hintTarget } from '../../lib/pairing';
import {
  DaemonFilesBackend,
  SftpFilesBackend,
  isPlainName,
  parseBreadcrumbs,
  parentPath,
  joinPath,
  type FileEntry,
  type FilesBackend,
} from '../../lib/files';
import { sftpBridge } from '../../lib/sftp';
import { DaemonTransferBackend } from '../../lib/daemonTransfer';
import { SftpTransferBackend } from '../../lib/sftpTransfer';
import { TransferSheet, useTransfers } from './TransferSheet';
import { filesTabCwd, setFilesTabCwd } from '../../lib/filesTabs';
import {
  activeTransfers,
  advanceTransfer,
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
import { Field } from '../../components/Form';
import { Icon } from '../../components/ui/icon';
import { Progress } from '../../components/ui/progress';
import { Text } from '../../components/ui/text';
import type { RootStackParamList } from '../../navigation/types';
import { useSettings } from '../../theme/SettingsProvider';
import { Breadcrumbs } from './Breadcrumbs';
import { FileListItem } from './FileListItem';
import { entryKey, formatSize, numberedName } from './filePresentation';

// One file browser, two backends: the daemon filesystem (fs.* over the control
// channel plus resumable transfers) and plain SSH SFTP (browse and metadata).
// The route param picks the backend; the capability table labels resume and
// integrity honestly (both are daemon-only). Names are rendered byte-faithful.

type Phase = 'init' | 'connecting' | 'hostKey' | 'ready' | 'error';
type Kind = 'daemon' | 'sftp';

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
// Matches the daemon chunk payload cap (1 MiB minus the 8-byte frame offset).
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
    kind: 'daemon' | 'ssh';
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
  const kindParam: 'daemon' | 'ssh' =
    embedded?.kind ?? route.params?.kind ?? 'daemon';

  const [phase, setPhase] = useState<Phase>('init');
  const [backend, setBackend] = useState<FilesBackend | null>(null);
  const [kind, setKind] = useState<Kind>('daemon');
  const tabId = embedded?.tabId ?? '';
  const [cwd, setCwd] = useState(() => filesTabCwd(tabId));
  const [entries, setEntries] = useState<FileEntry[] | null>(null);
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
  const pendingDownloadRef = useRef<string | null>(null);
  // The remote name a folder pick was started for, so the download can carry
  // on once the folder comes back.
  const pendingFolderPickRef = useRef<{ name: string; path: string } | null>(
    null,
  );
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

  // Daemon: connect over the transport (direct hints first, network errors
  // move on), then the control-channel backend plus the transfer engine.
  async function initDaemon(hostId: string): Promise<void> {
    setKind('daemon');
    try {
      const list = await getHosts().list();
      const h = list.find(x => x.id === hostId) ?? null;
      if (h === null) throw new Error('This host is no longer paired.');
      const t = getTransport();
      const status = await t.status(hostId);
      if (status.connected !== true) {
        let lastError: unknown = null;
        for (const hint of h.hints) {
          try {
            await t.connect(hostId, hintTarget(hint), {
              daemonPub: h.daemonPub,
            });
            lastError = null;
            break;
          } catch (e) {
            lastError = e;
            if (toRemotlyError(e, 'network').kind !== 'network') throw e;
          }
        }
        if (lastError !== null) throw lastError;
      }
      if (disposedRef.current) return;
      const b = new DaemonFilesBackend(req =>
        getTransport().control(hostId, req),
      );
      backendRef.current = b;
      xferRef.current = new DaemonTransferBackend(getTransport(), hostId);
      setBackend(b);
      setPhase('ready');
      loadDir(cwdRef.current);
    } catch (e) {
      if (disposedRef.current) return;
      log.error('files daemon connect failed', {
        message: userFacingMessage(toRemotlyError(e, 'network')),
      });
      setError(userFacingMessage(toRemotlyError(e, 'network')));
      setPhase('error');
    }
  }

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
    setKind('sftp');
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

  // --- transfers (daemon only) ---

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

  async function doDownload(
    picked: PickedFile,
    path: string,
    resumeFrom = 0,
  ): Promise<void> {
    const xb = xferRef.current;
    if (xb === null) return;
    setTransfer({
      kind: 'download',
      path: picked.name,
      received: resumeFrom,
      total: -1,
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
            activeXferRef.current = null;
            setTransfer({
              kind: 'download',
              path: picked.name,
              received: totalBytes,
              total: totalBytes,
              active: false,
              done: true,
            });
            void release(picked.uri).catch(() => undefined);
          },
          msg => {
            if (id !== null) settleTransfer(id, 'error', msg);
            activeXferRef.current = null;
            setTransfer({
              kind: 'download',
              path: picked.name,
              received: 0,
              total: -1,
              active: false,
              error: msg,
            });
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
            total: -1,
            resumable,
          },
          () => {
            void xb.cancel(handle.id).catch(() => undefined);
            // Cancelled from the sheet, possibly from another screen. The
            // partial file is dropped unless Resume can continue from it.
            if (!resumable) void discard(picked.uri).catch(() => undefined);
          },
          from => void doDownload(picked, path, resumable ? from : 0),
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

    try {
      const handle = await xb.startDownload(
        path,
        (_offset, bytes) => {
          const ax = activeXferRef.current;
          if (ax?.id !== handle.id) return;
          writes = writes.then(async () => {
            if (writeFailed !== null) return;
            try {
              await writeChunk(picked.uri, bytes);
            } catch (err) {
              writeFailed = err instanceof Error ? err.message : String(err);
              log.error('download sink write failed', { message: writeFailed });
            }
          });
          setTransfer(t => {
            if (t === null || t.kind !== 'download' || !t.active) return t;
            const received = t.received + bytes.length;
            advanceTransfer(handle.id, received);
            return {
              ...t,
              received,
              total: handle.size > 0 ? handle.size : t.total,
            };
          });
        },
        totalBytes => {
          activeXferRef.current = null;
          // The last chunks may still be in flight. Releasing the file before
          // they land truncates it, and reporting success would be a lie.
          void writes.then(() => {
            if (writeFailed !== null) {
              settleTransfer(handle.id, 'error', writeFailed);
              setTransfer({
                kind: 'download',
                path: picked.name,
                received: 0,
                total: -1,
                active: false,
                error: writeFailed,
              });
            } else {
              advanceTransfer(handle.id, totalBytes);
              settleTransfer(handle.id, 'done');
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
          });
        },
        msg => {
          activeXferRef.current = null;
          settleTransfer(handle.id, 'error', msg);
          setTransfer({
            kind: 'download',
            path: picked.name,
            received: 0,
            total: -1,
            active: false,
            error: msg,
          });
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
          total: handle.size,
          // This path appends through JS and cannot seek the destination, so
          // picking it back up starts over. The sheet says Retry rather than
          // Resume, which is what actually happens.
          resumable: false,
        },
        () => {
          void xb.cancel(handle.id).catch(() => undefined);
          void discard(picked.uri).catch(() => undefined);
        },
        () => void doDownload(picked, path, 0),
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

  function startDownload(name: string, path: string): void {
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
    void beginDownload(name, path);
  }

  /**
   * Resolves where a download should land before any bytes move.
   *
   * With a folder the app holds, an existing name is found first and the user
   * is asked. Without one the folder is requested; the create-document picker
   * is not used as a fallback, because it resolves a collision by renaming and
   * never reports that it did.
   */
  async function beginDownload(name: string, path: string): Promise<void> {
    // Read through the ref, not the captured value. The event subscription
    // that calls this is registered once on mount, so its closure holds the
    // settings as they were then: the folder just granted was saved and never
    // seen, and every download asked for it again.
    const folder = folderUriRef.current;
    const usable = folder !== '' && (await hasFolderAccess(folder));
    if (!usable) {
      pendingFolderPickRef.current = { name, path };
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
        setNameClash({ name, remotePath: path, existingUri: existing });
        return;
      }
      const uri = await createInFolder(folder, name);
      void doDownload({ uri, name, size: -1 }, path);
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
      { uri: c.existingUri, name: c.name, size: -1 },
      c.remotePath,
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
        void doDownload({ uri, name: candidate, size: -1 }, c.remotePath);
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
    const xb = xferRef.current;
    if (ax === null) return;
    void xb?.cancel(ax.id).catch(() => undefined);
    // A cancelled download leaves a partial file under the name the user
    // chose, which reads as a finished download. It is kept only where Resume
    // can pick it back up; an upload writes nothing locally, so its
    // destination is simply released.
    if (ax.kind === 'download' && xb?.capabilities.transferResume !== true) {
      void discard(ax.uri).catch(() => undefined);
    } else {
      void release(ax.uri).catch(() => undefined);
    }
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
    } else if (kindParam === 'ssh') {
      setPhase('connecting');
      void initSftp(hostIdParam);
    } else {
      setPhase('connecting');
      void initDaemon(hostIdParam);
    }

    const unsubs = [
      onPick(f => {
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
          void beginDownload(wanted.name, wanted.path);
          return;
        }
        const p = pendingDownloadRef.current;
        pendingDownloadRef.current = null;
        if (p !== null) void doDownload(f, p);
      }),
    ];

    return () => {
      disposedRef.current = true;
      unsubs.forEach(u => u());
      // The transfer backend deliberately survives: transfers run in the
      // background and the sheet shows them from anywhere in the app. Only the
      // daemon's own channel bookkeeping is torn down, and only when nothing
      // is still moving.
      const xb = xferRef.current;
      if (
        xb instanceof DaemonTransferBackend &&
        activeTransfers().length === 0
      ) {
        xb.dispose();
        xferRef.current = null;
      }
      activeXferRef.current = null;
      // SFTP holds a live connection per host. It is kept open while a
      // transfer is still using it, and closed otherwise.
      if (kindParam === 'ssh' && activeTransfers().length === 0)
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
  const renderEntry = React.useCallback(
    ({ item }: { item: FileEntry }) => (
      <FileListItem entry={item} onOpen={openEntry} onMenu={menuForEntry} />
    ),
    [openEntry, menuForEntry],
  );
  const canTransfer = xferRef.current !== null;
  const runningCount = useTransfers().filter(t => t.phase === 'active').length;
  const [sheetOpen, setSheetOpen] = useState(false);
  const openTransfers = React.useCallback(() => setSheetOpen(true), []);
  const closeTransfers = React.useCallback(() => setSheetOpen(false), []);
  const kindLabel = kind === 'sftp' ? 'SSH SFTP' : 'Daemon';
  const canResume = backend?.capabilities.transferResume ?? false;
  const canIntegrity = backend?.capabilities.transferIntegrity ?? false;

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
      icon: 'arrow-down-up' as const,
      // The count is in the name so it is announced, not only drawn.
      title:
        runningCount > 0 ? `Transfers, ${runningCount} running` : 'Transfers',
      onPress: openTransfers,
    },
  ];

  return (
    <Screen
      title="Files"
      subtitle={kindLabel}
      bare={embedded !== undefined}
      {...(embedded === undefined ? { onBack: closePage } : {})}
      actions={actions}
    >
      <TransferSheet open={sheetOpen} onClose={closeTransfers} />
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
        <View className="flex-1 items-center justify-center gap-2 p-6">
          <Text variant="title" className="text-center">
            {hostKey.changed ? 'Host key changed' : 'New host key'}
          </Text>
          <Text variant="muted" className="text-center">
            {hostKey.changed
              ? 'A different key than the one you approved was presented. This may be a sign of tampering. Only continue if you expect the key to change.'
              : 'This server presented a host key you have not seen before. Approving it stores the key for this host.'}
          </Text>
          <Text variant="code" className="text-center">
            {hostKey.algorithm}
          </Text>
          <Text variant="code" className="text-center">
            {hostKey.fingerprint}
          </Text>
          <View className="flex-row gap-2 py-2">
            <Button variant="outline" onPress={closePage}>
              <Text>Reject</Text>
            </Button>
            <Button onPress={acceptSftpKey}>
              <Text>Trust and continue</Text>
            </Button>
          </View>
        </View>
      ) : null}

      {phase === 'ready' && error !== '' ? (
        <Notice tone="danger" message={error} />
      ) : null}

      {phase === 'ready' ? (
        <FlatList
          data={entries ?? []}
          keyExtractor={e => entryKey(cwd, e)}
          className="flex-1"
          contentContainerStyle={{ paddingBottom: 48 }}
          ListHeaderComponent={
            parentPath(cwd) !== null ? <UpRow onPress={goUp} /> : undefined
          }
          ListEmptyComponent={
            entries !== null && entries.length === 0 ? (
              <Empty
                icon="folder-open"
                title="This folder is empty"
                message={
                  canTransfer
                    ? 'Upload a file to get started.'
                    : 'This host supports browsing and metadata only.'
                }
              />
            ) : undefined
          }
          ListFooterComponent={
            loading && (entries === null || more) ? (
              <View className="items-center px-8 py-4">
                <Progress label="Loading more entries" className="w-1/2" />
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
        <View className="gap-2 border-t border-border bg-card p-4">
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
          <View className="flex-row justify-end gap-2">
            {transfer.active ? (
              <Button
                variant="outline"
                size="sm"
                onPress={cancelTransfer}
                accessibilityLabel="Cancel transfer"
              >
                <Text>Cancel</Text>
              </Button>
            ) : null}
            {transfer.conflict === true ? (
              <Button
                size="sm"
                onPress={retryReplace}
                accessibilityLabel="Replace the existing file and retry"
              >
                <Text>Replace and retry</Text>
              </Button>
            ) : null}
            {!transfer.active || transfer.conflict === true ? (
              <Button variant="ghost" size="sm" onPress={dismissTransfer}>
                <Text>Dismiss</Text>
              </Button>
            ) : null}
          </View>
        </View>
      ) : null}

      {phase === 'ready' ? (
        <View className="flex-row items-center justify-between border-t border-border px-2 py-1">
          <Button
            variant="ghost"
            size="sm"
            onPress={() => loadDir(cwdRef.current)}
            accessibilityLabel="Refresh this folder"
          >
            <Icon name="refresh" size={16} />
            <Text>Refresh</Text>
          </Button>
          <Text variant="caption" className="ml-2 flex-shrink">
            {canTransfer
              ? `Transfers: ${canResume ? 'resume available' : 'no resume'}${
                  canIntegrity ? ', integrity checked' : ', no integrity check'
                }`
              : 'Browsing and metadata only.'}
          </Text>
        </View>
      ) : null}

      {/* A download whose name is already taken in the destination folder.
          The choice is the user's: overwriting silently is destructive, and
          renaming silently is what the system picker does and why a
          collision used to pass unnoticed. */}
      <Dialog open={nameClash !== null} onClose={() => setNameClash(null)}>
        <DialogHeader>
          <DialogTitle>{nameClash?.name ?? ''} already exists</DialogTitle>
        </DialogHeader>
        <DialogContent className="pb-5">
          <Text variant="muted">
            A file with this name is already in your download folder.
          </Text>
          <Button
            variant="ghost"
            className="justify-start"
            onPress={resolveKeepBoth}
          >
            <Icon name="copy" />
            <Text>Keep both</Text>
          </Button>
          <Button
            variant="ghost"
            className="justify-start"
            onPress={resolveReplace}
          >
            <Icon name="refresh" />
            <Text>Replace</Text>
          </Button>
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => setNameClash(null)}
          >
            <Icon name="x" />
            <Text>Cancel</Text>
          </Button>
        </DialogContent>
      </Dialog>

      {/* Entry action menu. */}
      <Dialog
        open={menuFor !== null && prompt === null}
        onClose={() => setMenuFor(null)}
      >
        <DialogHeader>
          <DialogTitle numberOfLines={1}>{menuFor ?? ''}</DialogTitle>
        </DialogHeader>
        <DialogContent className="pb-5">
          {canTransfer ? (
            <Button
              variant="ghost"
              className="justify-start"
              onPress={() => {
                const name = menuFor;
                if (name === null) return;
                setMenuFor(null);
                startDownload(name, joinPath(cwdRef.current, name));
              }}
            >
              <Icon name="file-down" />
              <Text>Download</Text>
            </Button>
          ) : null}
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => {
              if (menuFor !== null) beginRename(menuFor);
            }}
          >
            <Icon name="pencil" />
            <Text>Rename</Text>
          </Button>
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => {
              if (menuFor !== null) beginRemove(menuFor);
            }}
          >
            <Icon name="trash" className="text-destructive" />
            <Text className="text-destructive">Delete</Text>
          </Button>
        </DialogContent>
      </Dialog>

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
              the daemon reports a non-empty target as an error.
            </Text>
          )}
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" onPress={cancelPrompt}>
            <Text>Cancel</Text>
          </Button>
          <Button
            variant={prompt?.kind === 'remove' ? 'destructive' : 'default'}
            onPress={() => void submitPrompt()}
            accessibilityLabel={
              prompt?.kind === 'remove' ? 'Delete' : 'Confirm'
            }
          >
            <Text>{prompt?.kind === 'remove' ? 'Delete' : 'OK'}</Text>
          </Button>
        </DialogFooter>
      </Dialog>
    </Screen>
  );
}

// The parent-directory row. At module scope so FlatList's header type is
// stable across renders.
function UpRow({ onPress }: { onPress: () => void }): React.ReactElement {
  return (
    <Button
      variant="ghost"
      className="h-14 justify-start rounded-none px-4"
      accessibilityLabel="Up one level"
      onPress={onPress}
    >
      <Icon name="arrow-up" className="text-muted-foreground" />
      <Text className="text-muted-foreground">..</Text>
    </Button>
  );
}
