// The SSH host editor. One route handles add and edit.
//
// Credentials cross to the native store on submit and are never persisted
// here. In edit mode the fields start blank: an existing secret is never read
// back, so the only way to change it is to supply a new one.

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, ScrollView, View } from 'react-native';
import {
  useNavigation,
  useRoute,
  type RouteProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { Screen } from '../../components/Screen';
import { KeyboardLifted } from '../../components/KeyboardLifted';
import { Field, FormSection } from '../../components/Form';
import { Loading, Notice } from '../../components/States';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toast } from '../../components/Toast';
import { Button } from '../../components/ui/button';
import { Icon } from '../../components/ui/icon';
import { Segmented } from '../../components/ui/segmented';
import { Text } from '../../components/ui/text';
import {
  sshHostDisplayName,
  sshHosts,
  type SshHostCredentialParams,
  type SshHostView,
} from '../../lib/sshHosts';
import { pickFile, onPick, readFileText, release } from '../../lib/fileIO';
import { encodeBase64String } from '../../lib/base64';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';
import { log } from '../../lib/log';
import type { RootStackParamList } from '../../navigation/types';

type Auth = 'key' | 'password';
type Nav = NativeStackNavigationProp<RootStackParamList>;

// Port bounds the native store enforces; mirrored here so the error appears
// before a round trip. The store stays authoritative.
const PORT_MIN = 1;
const PORT_MAX = 65535;

interface TestState {
  running: boolean;
  ok?: boolean;
  message?: string;
  fingerprint?: string;
  algorithm?: string;
  changed?: boolean;
}

export function SshHostEditorScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProp<RootStackParamList, 'SshHostEditor'>>();
  const hostId = route.params?.hostId;
  const editing = typeof hostId === 'string' && hostId !== '';

  const [loading, setLoading] = useState(editing);
  const [existing, setExisting] = useState<SshHostView | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [host, setHost] = useState('');
  const [port, setPort] = useState('22');
  const [username, setUsername] = useState('');
  const [auth, setAuth] = useState<Auth>('key');
  const [keyContents, setKeyContents] = useState('');
  const [keyFileName, setKeyFileName] = useState('');
  const [passphrase, setPassphrase] = useState('');
  const [password, setPassword] = useState('');
  // In edit mode the credential is only touched when the user asks for it.
  const [replaceCredential, setReplaceCredential] = useState(!editing);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [test, setTest] = useState<TestState>({ running: false });
  const [confirmEndpoint, setConfirmEndpoint] = useState(false);

  useEffect(() => {
    if (!editing) return;
    let cancelled = false;
    void sshHosts
      .list()
      .then(list => {
        if (cancelled) return;
        const found = list.find(h => h.id === hostId) ?? null;
        if (found === null) {
          setError('That host is no longer saved.');
          setLoading(false);
          return;
        }
        setExisting(found);
        setDisplayName(found.displayName);
        setHost(found.host);
        setPort(String(found.port));
        setUsername(found.username);
        setAuth(found.authKind === 1 ? 'key' : 'password');
        setLoading(false);
      })
      .catch(e => {
        if (cancelled) return;
        setError(userFacingMessage(toRemotlyError(e, 'storage')));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [editing, hostId]);

  // Imports a private key through the system picker. The URI is read once and
  // released immediately; the bytes are not retained.
  useEffect(
    () =>
      onPick(f => {
        void readFileText(f.uri)
          .then(text => {
            setKeyContents(text);
            setKeyFileName(f.name !== '' ? f.name : 'key');
            setError('');
          })
          .catch(() => setError('That file could not be read.'))
          .finally(() => {
            void release(f.uri).catch(() => undefined);
          });
      }),
    [],
  );

  const portNum = Number(port);
  const portValid =
    Number.isInteger(portNum) && portNum >= PORT_MIN && portNum <= PORT_MAX;
  const hostValid = host.trim() !== '';
  const userValid = username.trim() !== '';
  const secretValid =
    auth === 'password' ? password.length > 0 : keyContents.trim() !== '';
  // Editing without replacing the credential leaves the stored one in place.
  const credentialValid = replaceCredential ? secretValid : true;
  const valid = hostValid && userValid && portValid && credentialValid;

  const endpointChanged =
    existing !== null &&
    (host.trim() !== existing.host || portNum !== existing.port);

  const credential = useMemo<SshHostCredentialParams>(
    () => ({
      useKey: auth === 'key',
      privateKey: auth === 'key' ? encodeBase64String(keyContents) : undefined,
      passphrase: auth === 'key' ? passphrase : undefined,
      password: auth === 'password' ? password : undefined,
    }),
    [auth, keyContents, passphrase, password],
  );

  const close = useCallback(() => navigation.goBack(), [navigation]);

  const runTest = useCallback(async () => {
    if (!hostValid || !userValid || !portValid || !secretValid) return;
    setTest({ running: true });
    try {
      const result = await sshHosts.testConnection({
        ...(editing && hostId ? { hostId } : {}),
        host: host.trim(),
        port: portNum,
        username: username.trim(),
        credential,
      });
      setTest({
        running: false,
        ok: result.ok,
        message: result.ok
          ? 'The server accepted the credential.'
          : result.message || 'The connection did not succeed.',
        fingerprint: result.hostKeyFingerprint,
        algorithm: result.hostKeyAlgorithm,
        changed: result.hostKeyChanged,
      });
    } catch (e) {
      setTest({
        running: false,
        ok: false,
        message: userFacingMessage(toRemotlyError(e, 'network')),
      });
    }
  }, [
    hostValid,
    userValid,
    portValid,
    secretValid,
    editing,
    hostId,
    host,
    portNum,
    username,
    credential,
  ]);

  const persist = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      if (editing && hostId) {
        const updated = await sshHosts.update(hostId, {
          displayName: displayName.trim(),
          host: host.trim(),
          port: portNum,
          username: username.trim(),
          ...(replaceCredential ? { credential } : {}),
        });
        // The id changes with the endpoint, so the caller must navigate using
        // the returned record rather than the id it arrived with.
        log.info('ssh host updated', { changed: updated.id !== hostId });
      } else {
        await sshHosts.add({
          displayName: displayName.trim(),
          host: host.trim(),
          port: portNum,
          username: username.trim(),
          ...credential,
        });
      }
      // The plaintext leaves state as soon as the native call returns.
      setPassword('');
      setPassphrase('');
      setKeyContents('');
      close();
    } catch (e) {
      setError(userFacingMessage(toRemotlyError(e, 'storage')));
      setBusy(false);
    }
  }, [
    editing,
    hostId,
    displayName,
    host,
    portNum,
    username,
    replaceCredential,
    credential,
    close,
  ]);

  const save = useCallback(() => {
    if (!valid || busy) return;
    // Changing the endpoint drops the accepted host keys, so it is confirmed
    // rather than done silently.
    if (endpointChanged) {
      setConfirmEndpoint(true);
      return;
    }
    void persist();
  }, [valid, busy, endpointChanged, persist]);

  const importKey = useCallback(() => {
    pickFile('upload').catch(e => {
      setError(userFacingMessage(toRemotlyError(e, 'unknown')));
    });
  }, []);

  if (loading) {
    return (
      <Screen title="Edit SSH host" onBack={close}>
        <Loading label="Loading host" />
      </Screen>
    );
  }

  const fallbackName =
    displayName.trim() === '' && username.trim() !== '' && host.trim() !== ''
      ? `${username.trim()}@${host.trim()}`
      : '';

  return (
    <Screen
      title={editing ? 'Edit SSH host' : 'Add SSH host'}
      {...(editing && existing
        ? { subtitle: sshHostDisplayName(existing) }
        : {})}
      onBack={close}
    >
      <KeyboardLifted className="flex-1">
        <ScrollView
          contentContainerStyle={{ paddingBottom: 48 }}
          keyboardShouldPersistTaps="handled"
          // Keeps the focused field clear of the keyboard as focus moves between
          // fields, rather than only on the first one.
          automaticallyAdjustKeyboardInsets={false}
        >
          {error !== '' ? <Notice tone="danger" message={error} /> : null}

          <FormSection title="Identity">
            <Field
              label="Label"
              value={displayName}
              onChangeText={setDisplayName}
              placeholder="Optional"
              {...(fallbackName !== ''
                ? { hint: `Shown as ${fallbackName}` }
                : {})}
            />
          </FormSection>

          <FormSection title="Connection">
            <Field
              label="Host"
              value={host}
              onChangeText={setHost}
              placeholder="server.example.com"
              autoCapitalize="none"
              autoCorrect={false}
              error={
                host !== '' && !hostValid
                  ? 'Enter a valid hostname or address.'
                  : ''
              }
            />
            <Field
              label="Port"
              value={port}
              onChangeText={setPort}
              placeholder="22"
              keyboardType="number-pad"
              error={
                port !== '' && !portValid
                  ? 'The port must be between 1 and 65535.'
                  : ''
              }
            />
            <Field
              label="Username"
              value={username}
              onChangeText={setUsername}
              placeholder="alice"
              autoCapitalize="none"
              autoCorrect={false}
              error={username !== '' && !userValid ? 'Enter a username.' : ''}
            />
            {endpointChanged ? (
              <Notice
                tone="danger"
                message="Changing the address or port establishes trust again: the accepted host key is not carried over."
              />
            ) : null}
          </FormSection>

          <FormSection title="Authentication">
            {editing && !replaceCredential ? (
              <View className="gap-2">
                <Text variant="callout">
                  {existing?.authKind === 1
                    ? 'A private key is saved for this host.'
                    : 'A password is saved for this host.'}
                </Text>
                <Button
                  variant="outline"
                  onPress={() => setReplaceCredential(true)}
                  accessibilityLabel="Replace the saved credential"
                >
                  <Text>Replace credential</Text>
                </Button>
              </View>
            ) : (
              <>
                <Segmented
                  value={auth}
                  onChange={setAuth}
                  options={[
                    { value: 'key', label: 'Private key' },
                    { value: 'password', label: 'Password' },
                  ]}
                />
                {auth === 'key' ? (
                  <>
                    <Button variant="outline" onPress={importKey}>
                      <Icon name="file" />
                      <Text>
                        {keyFileName !== ''
                          ? `Imported ${keyFileName}`
                          : 'Import a key file'}
                      </Text>
                    </Button>
                    <Field
                      label="Private key"
                      value={keyContents}
                      onChangeText={setKeyContents}
                      multiline
                      autoCapitalize="none"
                      autoCorrect={false}
                      className="h-28 py-3"
                    />
                    <Field
                      label="Passphrase"
                      value={passphrase}
                      onChangeText={setPassphrase}
                      secureTextEntry
                      autoCapitalize="none"
                      autoCorrect={false}
                    />
                  </>
                ) : (
                  <Field
                    label="Password"
                    value={password}
                    onChangeText={setPassword}
                    secureTextEntry
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                )}
              </>
            )}
          </FormSection>

          <FormSection
            title="Verification"
            description="Connects once to check the address and credential. Nothing is saved."
          >
            <Button
              variant="outline"
              disabled={
                test.running ||
                !hostValid ||
                !userValid ||
                !portValid ||
                !secretValid
              }
              onPress={() => void runTest()}
            >
              <Icon name="network" />
              <Text>Test connection</Text>
            </Button>
            {test.running ? (
              <View className="flex-row items-center gap-2">
                <ActivityIndicator accessibilityLabel="Testing the connection" />
                <Text variant="callout">Connecting</Text>
              </View>
            ) : null}
            {!test.running && test.ok !== undefined ? (
              <Notice
                tone={test.ok ? 'ok' : 'danger'}
                message={test.message ?? ''}
              />
            ) : null}
            {!test.running && test.fingerprint ? (
              <View className="gap-1">
                <Text variant="caption">
                  {test.changed
                    ? 'The server presented a different key than the one accepted before.'
                    : 'Host key presented by the server'}
                </Text>
                <Text variant="code">
                  {`${test.algorithm ?? ''} ${test.fingerprint}`}
                </Text>
                <Text variant="caption">
                  Passing the test does not trust this key. You confirm it the
                  first time you connect.
                </Text>
              </View>
            ) : null}
          </FormSection>

          {editing && existing && existing.knownKeys.length > 0 ? (
            <FormSection title="Accepted host keys">
              {existing.knownKeys.map(k => (
                <Text key={k.fingerprint} variant="code">
                  {`${k.algorithm} ${k.fingerprint}`}
                </Text>
              ))}
            </FormSection>
          ) : null}

          <View className="flex-row justify-end gap-2 p-4">
            <Button variant="outline" onPress={close} disabled={busy}>
              <Text>Cancel</Text>
            </Button>
            <Button onPress={save} disabled={!valid || busy}>
              {busy ? <ActivityIndicator size="small" /> : null}
              <Text>Save</Text>
            </Button>
          </View>
        </ScrollView>
      </KeyboardLifted>

      <ConfirmDialog
        visible={confirmEndpoint}
        title="Change the address?"
        message="This host is identified by its address and port. Changing them establishes trust again: the accepted host key is not carried over, and you confirm the new one the next time you connect."
        confirmLabel="Change"
        busy={busy}
        onConfirm={() => {
          setConfirmEndpoint(false);
          void persist();
        }}
        onDismiss={() => setConfirmEndpoint(false)}
      />

      <Toast message={notice} onDismiss={() => setNotice('')} />
    </Screen>
  );
}
