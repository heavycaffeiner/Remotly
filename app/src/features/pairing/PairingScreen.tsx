// The pairing flow: scan or paste a remotly://pair URI, review the daemon
// identity, connect, and save the host.
//
// The secret and the token never reach the UI. Only the daemon name, a short
// fingerprint, the expiry, and the routes are shown.

import React, {
  useCallback,
  useEffect,
  useReducer,
  useRef,
  useState,
} from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useNavigation, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import NativeCamera from '../../specs/NativeRemotlyCamera';
import { Screen } from '../../components/Screen';
import { KeyboardLifted } from '../../components/KeyboardLifted';
import { ErrorState, Notice } from '../../components/States';
import { Button } from '../../components/ui/button';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '../../components/ui/card';
import { Icon } from '../../components/ui/icon';
import { Input } from '../../components/ui/input';
import { Segmented } from '../../components/ui/segmented';
import { Text } from '../../components/ui/text';
import { encodeBase64Url } from '../../lib/base64url';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';
import { getHosts } from '../../lib/hosts';
import {
  HINT_RELAY,
  PAIRING_URI_PREFIX,
  type PairingHint,
} from '../../lib/pairing';
import { getTransport } from '../../lib/transport';
import { log } from '../../lib/log';
import type { RootStackParamList } from '../../navigation/types';
import {
  connectingLabel,
  formatExpiry,
  initialPairingState,
  isExpired,
  pairingReducer,
  previewFromUri,
  type PairingPreview,
} from './pairingMachine';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const HINT_LABELS: Record<number, string> = {
  0: 'IPv4',
  1: 'IPv6',
  2: 'Name',
  3: 'Relay',
};

// Each dial is bounded by the native transport: 10s to connect and 10s to
// finish the handshake. Nothing shorter may be imposed here. A pairing token
// is single-use and is claimed part-way through the handshake, so abandoning
// a dial early can burn the token on an attempt that was still going to
// succeed, and every remaining target then fails with token_used.

export function PairingScreen({
  route,
}: {
  route: RouteProp<RootStackParamList, 'Pairing'>;
}): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const [state, dispatch] = useReducer(pairingReducer, initialPairingState);
  const [manual, setManual] = useState('');
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState('');

  // Guards a single connect attempt and a single temporary transport.
  const connecting = useRef(false);
  const tempHostId = useRef<string | null>(null);
  const disposed = useRef(false);

  const processUri = useCallback((uri: string, source: 'scan' | 'paste') => {
    if (connecting.current) return;
    const result = previewFromUri(uri);
    if (!result.ok) {
      // No dedupe on failure: the same code must be scannable again after
      // the user reads the error.
      dispatch({ type: 'parseFailed', source, message: result.message });
      return;
    }
    log.debug('pairing payload parsed');
    dispatch({ type: 'parsed', preview: result.preview });
  }, []);

  useEffect(() => {
    disposed.current = false;
    return () => {
      disposed.current = true;
    };
  }, []);

  // Launches Google's scanner activity. It owns the camera, the preview, and
  // the camera permission, so nothing here asks for one.
  const openScanner = useCallback(() => {
    if (scanning) return;
    setScanning(true);
    setScanError('');
    void NativeCamera.scanCode()
      .then(result => {
        if (disposed.current) return;
        const value = result.value ?? '';
        // An empty value means the user backed out, which is not a failure.
        if (value !== '') processUri(value, 'scan');
      })
      .catch(() => {
        if (disposed.current) return;
        setScanError(
          'The scanner could not start on this device. Paste the pairing link instead.',
        );
      })
      .finally(() => {
        if (!disposed.current) setScanning(false);
      });
  }, [scanning, processUri]);

  // Fills the field from the clipboard. The value is not submitted: the user
  // sees what was pasted before it is used.
  const pasteFromClipboard = useCallback(() => {
    void NativeCamera.readClipboard()
      .then(result => {
        const text = (result.value ?? '').trim();
        if (!disposed.current && text !== '') setManual(text);
      })
      .catch(() => undefined);
  }, []);

  // Warm and cold deep links arrive as the `d` query param.
  const deepLink = route.params?.d;
  useEffect(() => {
    if (deepLink) processUri(`${PAIRING_URI_PREFIX}${deepLink}`, 'scan');
  }, [deepLink, processUri]);

  // Cold-start fallback: the OS can deliver the intent before JS is ready, so
  // the native side holds it one-shot.
  useEffect(() => {
    let cancelled = false;
    getHosts()
      .takePendingPairingURI()
      .then(uri => {
        if (!cancelled && uri !== '') processUri(uri, 'scan');
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [processUri]);

  const closeTemp = useCallback(async () => {
    const id = tempHostId.current;
    if (id === null) return;
    tempHostId.current = null;
    await getTransport()
      .close(id)
      .catch(() => undefined);
  }, []);

  // A temporary pairing transport must never outlive the screen.
  useEffect(() => () => void closeTemp(), [closeTemp]);

  const doConnect = useCallback(
    async (preview: PairingPreview) => {
      if (connecting.current) return;
      connecting.current = true;
      dispatch({ type: 'connect', route: 'direct' });

      const id = `pairing-${Date.now().toString(36)}-${Math.random()
        .toString(36)
        .slice(2, 8)}`;
      tempHostId.current = id;
      const tokenID = encodeBase64Url(preview.payload.tokenID);
      const psk = encodeBase64Url(preview.payload.secret);
      const relayId = preview.relay
        ? encodeBase64Url(preview.payload.daemonPub.slice(0, 16))
        : undefined;

      let connected = false;
      let lastError: unknown = null;

      // Targets are dialed one at a time, each to completion. A pairing token
      // is single-use: the first handshake to reach the daemon claims it and
      // every other one is refused with token_used, so the targets can be
      // neither raced nor abandoned early. Ordering is what keeps this quick:
      // the daemon lists the address most likely to answer first.
      for (let i = 0; i < preview.targets.length; i++) {
        const target = preview.targets[i] as string;
        const attemptId = `${id}-${i}`;
        try {
          await getTransport().connect(attemptId, target, { tokenID, psk });
          tempHostId.current = attemptId;
          connected = true;
          break;
        } catch (e) {
          lastError = e;
          // The attempt may have registered a transport before failing, and a
          // timed-out dial is still running inside the hub. Closing releases
          // the slot either way.
          void getTransport()
            .close(attemptId)
            .catch(() => undefined);
          const err = toRemotlyError(e, 'network');
          // Only a network failure is worth trying the next address for. An
          // auth or protocol failure means this payload will not work
          // anywhere, and a claimed token will not un-claim itself.
          if (err.kind !== 'network') {
            await closeTemp();
            connecting.current = false;
            if (!disposed.current) {
              dispatch({
                type: 'connectFailed',
                message: userFacingMessage(err),
              });
            }
            return;
          }
        }
      }

      if (!connected && preview.relay) {
        if (!disposed.current)
          dispatch({ type: 'connectRoute', route: 'relay' });
        try {
          await getTransport().connect(id, preview.relay.target, {
            tokenID,
            psk,
            relayTarget: preview.relay.target,
            relayId,
            relayOnly: true,
          });
          connected = true;
        } catch (e) {
          lastError = e;
        }
      }

      if (!connected) {
        await closeTemp();
        connecting.current = false;
        if (!disposed.current) {
          dispatch({
            type: 'connectFailed',
            message: userFacingMessage(toRemotlyError(lastError, 'network')),
          });
        }
        return;
      }

      const storedHints = preview.relay
        ? [
            ...preview.usableHints,
            {
              kind: HINT_RELAY,
              addr: preview.relay.host,
              port: preview.relay.port,
            },
          ]
        : preview.usableHints;

      try {
        const added = await getHosts().add({
          daemonName: preview.payload.daemonName,
          daemonPub: encodeBase64Url(preview.payload.daemonPub),
          hints: storedHints,
        });
        await closeTemp();
        if (!disposed.current) {
          dispatch({
            type: 'connected',
            hostId: added.id,
            duplicate: added.duplicate,
          });
        }
      } catch (e) {
        await closeTemp();
        if (!disposed.current) {
          dispatch({
            type: 'connectFailed',
            message: userFacingMessage(toRemotlyError(e, 'storage')),
          });
        }
      } finally {
        connecting.current = false;
      }
    },
    [closeTemp],
  );

  const submitManual = useCallback(() => {
    const uri = manual.trim();
    if (uri === '') return;
    processUri(uri, 'paste');
  }, [manual, processUri]);

  const retry = useCallback(() => {
    setManual('');
    dispatch({ type: 'reset' });
  }, []);

  const close = useCallback(() => navigation.goBack(), [navigation]);

  const openHost = useCallback(
    (hostId: string) => {
      navigation.replace('Workspace', { hostId });
    },
    [navigation],
  );

  return (
    <Screen title="Pair a host" onBack={close}>
      {/* The manual step is a text field with its actions underneath, so the
          keyboard covered the Continue button on a resize-less window. */}
      <KeyboardLifted className="flex-1 gap-3 px-4 py-3">
        {state.step === 'input' ? (
          <InputStep
            method={state.method}
            scanError={scanError}
            scanning={scanning}
            manual={manual}
            onMethod={m => dispatch({ type: 'method', method: m })}
            onScan={openScanner}
            onManualChange={setManual}
            onManualSubmit={submitManual}
            onPasteFromClipboard={pasteFromClipboard}
          />
        ) : null}

        {state.step === 'review' ? (
          <ReviewStep
            preview={state.preview}
            onCancel={retry}
            onConnect={() => void doConnect(state.preview)}
          />
        ) : null}

        {state.step === 'connecting' ? (
          <View
            accessibilityRole="progressbar"
            className="flex-1 items-center justify-center gap-3"
          >
            <ActivityIndicator size="large" />
            <Text variant="title">{connectingLabel(state.route)}</Text>
            <Text variant="muted" className="text-center">
              Completing the secure pairing with {state.preview.daemonName}.
            </Text>
          </View>
        ) : null}

        {state.step === 'success' ? (
          <View className="flex-1 items-center justify-center gap-3">
            <Icon name="circle-check" size={48} className="text-primary" />
            <Text variant="h3" className="text-center">
              {state.duplicate
                ? `${state.daemonName} was already paired`
                : `Paired with ${state.daemonName}`}
            </Text>
            <Text variant="muted" className="text-center">
              {state.duplicate
                ? 'Its connection details were refreshed.'
                : 'The host is saved and pinned to its identity.'}
            </Text>
            <View className="flex-row gap-2 pt-2">
              <Button variant="outline" onPress={close}>
                <Text>Done</Text>
              </Button>
              <Button onPress={() => openHost(state.hostId)}>
                <Text>Open host</Text>
              </Button>
            </View>
          </View>
        ) : null}

        {state.step === 'error' ? (
          <ErrorState
            title="Pairing failed"
            message={state.message}
            onRetry={retry}
          />
        ) : null}
      </KeyboardLifted>
    </Screen>
  );
}

interface InputStepProps {
  method: 'scan' | 'paste';
  /** Empty when the last scan attempt did not fail. */
  scanError: string;
  scanning: boolean;
  manual: string;
  onMethod: (m: 'scan' | 'paste') => void;
  onScan: () => void;
  onManualChange: (v: string) => void;
  onManualSubmit: () => void;
  onPasteFromClipboard: () => void;
}

function InputStep({
  method,
  scanError,
  scanning,
  manual,
  onMethod,
  onScan,
  onManualChange,
  onManualSubmit,
  onPasteFromClipboard,
}: InputStepProps): React.ReactElement {
  return (
    <View className="gap-3">
      <Segmented
        value={method}
        onChange={onMethod}
        options={[
          { value: 'scan', label: 'Scan' },
          { value: 'paste', label: 'Paste link' },
        ]}
      />

      {method === 'scan' ? (
        <View className="gap-3">
          <View className="items-center gap-3 rounded-lg bg-card p-6">
            <Icon name="scan" size={48} className="text-muted-foreground" />
            <Text variant="title" className="text-center">
              Scan the pairing code
            </Text>
            <Text variant="muted" className="text-center">
              Opens the camera to read the QR code printed by `remotly pair`.
            </Text>
            <Button disabled={scanning} onPress={onScan}>
              {scanning ? <ActivityIndicator size="small" /> : null}
              <Text>{scanning ? 'Scanning' : 'Open scanner'}</Text>
            </Button>
            <Button variant="ghost" onPress={() => onMethod('paste')}>
              <Text>Paste link instead</Text>
            </Button>
          </View>

          {scanError !== '' ? (
            <Notice
              tone="danger"
              message={scanError}
              action={{
                label: 'Paste instead',
                onPress: () => onMethod('paste'),
              }}
            />
          ) : null}
        </View>
      ) : (
        <View className="gap-3">
          <Input
            value={manual}
            onChangeText={onManualChange}
            placeholder={PAIRING_URI_PREFIX}
            accessibilityLabel="Pairing link"
            autoCapitalize="none"
            autoCorrect={false}
            autoComplete="off"
            spellCheck={false}
            multiline
            className="h-24 py-3"
          />
          <Text variant="muted">
            The link starts with {PAIRING_URI_PREFIX} and is printed by `remotly
            pair`.
          </Text>
          <View className="flex-row gap-2">
            <Button
              variant="outline"
              className="flex-1"
              onPress={onPasteFromClipboard}
            >
              <Icon name="clipboard" />
              <Text>Paste</Text>
            </Button>
            <Button
              className="flex-1"
              onPress={onManualSubmit}
              disabled={manual.trim() === ''}
            >
              <Text>Use link</Text>
            </Button>
          </View>
        </View>
      )}
    </View>
  );
}

function ReviewStep({
  preview,
  onCancel,
  onConnect,
}: {
  preview: PairingPreview;
  onCancel: () => void;
  onConnect: () => void;
}): React.ReactElement {
  const expired = isExpired(preview);
  const relayRow: PairingHint | null = preview.relay
    ? {
        kind: HINT_RELAY,
        addr: preview.relay.host,
        port: preview.relay.port,
      }
    : null;
  const rows: PairingHint[] = [
    ...preview.usableHints,
    ...(relayRow ? [relayRow] : []),
  ];

  return (
    <View className="gap-3">
      <Card>
        <CardHeader>
          <CardTitle>{`Pair with ${preview.daemonName}?`}</CardTitle>
        </CardHeader>
        <CardContent>
          <Text variant="callout">
            The pairing code is single use. This device keeps the host identity
            and never stores the pairing secret.
          </Text>
          <Text
            variant="code"
            className="text-muted-foreground"
            accessibilityLabel={`Host identity ${preview.fingerprint}`}
          >
            {preview.fingerprint}
          </Text>
          <Text variant="callout">
            {expired
              ? 'This code has expired.'
              : `Expires ${formatExpiry(preview.expiry)}`}
          </Text>

          <View className="gap-1 pt-1">
            {rows.map(h => (
              <View
                key={`${h.kind}:${h.addr}:${h.port}`}
                className="flex-row items-center gap-2"
              >
                <Text variant="caption" className="w-14">
                  {HINT_LABELS[h.kind] ?? 'Hint'}
                </Text>
                <Text variant="code" className="flex-1">
                  {`${h.addr}:${h.port}`}
                </Text>
              </View>
            ))}
          </View>
        </CardContent>
      </Card>

      {expired ? (
        <Notice
          tone="danger"
          message="Run `remotly pair` again to get a fresh code."
        />
      ) : null}

      <View className="flex-row justify-end gap-2">
        <Button variant="outline" onPress={onCancel}>
          <Text>Cancel</Text>
        </Button>
        <Button onPress={onConnect} disabled={expired}>
          <Text>Connect</Text>
        </Button>
      </View>
    </View>
  );
}
