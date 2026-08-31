// The Sessions destination: live sessions across every paired host.
//
// One connection per host (the hub owns them by host id), each refreshed
// independently, so one unreachable host is a partial result rather than a
// failed screen. A row opens that host's workspace.

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Pressable, RefreshControl, ScrollView, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { Screen } from '../../components/Screen';
import {
  Empty,
  ErrorState,
  Loading,
  Notice,
  SectionHeader,
  StatusChip,
} from '../../components/States';
import { Icon } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import { getHosts, type HostRecord } from '../../lib/hosts';
import { getTransport } from '../../lib/transport';
import { hintTarget } from '../../lib/pairing';
import {
  applyRefresh,
  beginRefresh,
  compareSessions,
  createOverview,
  relativeAge,
  removeHost,
  type OverviewState,
} from '../../lib/overview';
import { listSessions, rawSessionLastActivityMs } from '../../lib/sessions';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';
import { log } from '../../lib/log';
import { themeColors, useAppliedColorScheme } from '../../theme/useColorScheme';
import type { RootStackParamList } from '../../navigation/types';

type Phase = 'loading' | 'ready' | 'error';
type Nav = NativeStackNavigationProp<RootStackParamList>;

export function SessionsScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const scheme = useAppliedColorScheme();

  const [phase, setPhase] = useState<Phase>('loading');
  const [hosts, setHosts] = useState<HostRecord[]>([]);
  const [ov, setOv] = useState<OverviewState>(() => createOverview());
  const [errorText, setErrorText] = useState('');

  const ovRef = useRef(ov);
  const hostsRef = useRef<HostRecord[]>([]);
  const disposed = useRef(false);

  useEffect(() => {
    ovRef.current = ov;
  }, [ov]);

  const commitOv = useCallback((next: OverviewState) => {
    ovRef.current = next;
    if (!disposed.current) setOv(next);
  }, []);

  // Connects if needed and lists the host's sessions. The generation token
  // rejects out-of-order responses.
  const refreshHost = useCallback(
    async (h: HostRecord) => {
      const { state, generation } = beginRefresh(ovRef.current, h.id);
      commitOv(state);
      const t = getTransport();
      try {
        const status = await t.status(h.id);
        if (status.connected !== true) {
          let lastError: unknown = null;
          for (const hint of h.hints) {
            try {
              await t.connect(h.id, hintTarget(hint), {
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
        const sessions = await listSessions(h.id);
        commitOv(
          applyRefresh(ovRef.current, {
            hostId: h.id,
            generation,
            ok: true,
            sessions: sessions.map(s => ({
              sessionId: s.id,
              title: s.title,
              kind: s.kind,
              lastActivity: Math.floor(rawSessionLastActivityMs(s) / 1000),
              running: s.running,
              preview: s.preview,
            })),
          }),
        );
      } catch (e) {
        log.warn('sessions refresh failed', {
          message: userFacingMessage(toRemotlyError(e, 'network')),
        });
        commitOv(
          applyRefresh(ovRef.current, { hostId: h.id, generation, ok: false }),
        );
      }
    },
    [commitOv],
  );

  const refreshAll = useCallback(
    async (list: HostRecord[]) => {
      // Hosts removed since the last load are dropped; their in-flight
      // responses are rejected by the removed set.
      const ids = new Set(list.map(x => x.id));
      let state = ovRef.current;
      for (const tracked of state.generation.keys()) {
        if (!ids.has(tracked)) state = removeHost(state, tracked);
      }
      commitOv(state);
      await Promise.all(list.map(h => refreshHost(h)));
      if (!disposed.current) setPhase('ready');
    },
    [commitOv, refreshHost],
  );

  const load = useCallback(async () => {
    setPhase('loading');
    try {
      const list = await getHosts().list();
      if (disposed.current) return;
      hostsRef.current = list;
      setHosts(list);
      setErrorText('');
      await refreshAll(list);
    } catch (e) {
      if (disposed.current) return;
      const message = userFacingMessage(toRemotlyError(e, 'storage'));
      log.error('failed to load hosts', { message });
      setErrorText(message);
      setPhase('error');
    }
  }, [refreshAll]);

  const loadRef = useRef(load);
  loadRef.current = load;

  useFocusEffect(
    useCallback(() => {
      void loadRef.current();
    }, []),
  );

  useEffect(() => {
    disposed.current = false;
    const hostsAtMount = hostsRef.current;
    void loadRef.current();
    return () => {
      disposed.current = true;
      for (const h of hostsAtMount) {
        void getTransport()
          .close(h.id)
          .catch(() => undefined);
      }
    };
  }, []);

  // The hub allows one connection per host id, so hand the slot to the
  // workspace, which reconnects on demand.
  const openWorkspace = useCallback(
    (h: HostRecord) => {
      void getTransport()
        .close(h.id)
        .catch(() => undefined);
      navigation.navigate('Workspace', { hostId: h.id });
    },
    [navigation],
  );

  const nowSec = Math.floor(Date.now() / 1000);
  const failedHosts = useMemo(
    () => hosts.filter(h => ov.error.get(h.id) === true),
    [hosts, ov],
  );
  const totalSessions = useMemo(
    () => hosts.reduce((n, h) => n + (ov.sessions.get(h.id)?.length ?? 0), 0),
    [hosts, ov],
  );

  if (phase === 'error') {
    return (
      <Screen title="Sessions">
        <ErrorState
          title="Sessions unavailable"
          message={errorText || 'The host store could not be read.'}
          onRetry={() => void load()}
        />
      </Screen>
    );
  }

  if (phase === 'loading' && hosts.length === 0) {
    return (
      <Screen title="Sessions">
        <Loading label="Loading sessions" />
      </Screen>
    );
  }

  if (phase === 'ready' && hosts.length === 0) {
    return (
      <Screen title="Sessions">
        <Empty
          icon="layout-dashboard"
          title="No hosts yet"
          message="Pair a Remotly host to see its live sessions here."
          action={{
            label: 'Pair Remotly host',
            onPress: () => navigation.navigate('Pairing', {}),
          }}
        />
      </Screen>
    );
  }

  return (
    <Screen title="Sessions" subtitle="Live sessions across your hosts">
      <ScrollView
        contentContainerStyle={{ paddingBottom: 48 }}
        refreshControl={
          <RefreshControl
            refreshing={phase === 'loading'}
            onRefresh={() => void load()}
            colors={[themeColors[scheme].primary]}
            tintColor={themeColors[scheme].primary}
          />
        }
      >
        {phase === 'ready' &&
        totalSessions === 0 &&
        failedHosts.length === 0 ? (
          <View className="items-center gap-1 p-6">
            <Text variant="title">No live sessions</Text>
            <Text variant="muted">Open a host workspace to start one.</Text>
          </View>
        ) : null}

        {hosts.map(h => {
          const sessions = (ov.sessions.get(h.id) ?? [])
            .slice()
            .sort(compareSessions);
          const errored = ov.error.get(h.id) === true;
          const loading = ov.loading.get(h.id) === true;
          if (sessions.length === 0 && !errored && !loading) return null;
          return (
            <View key={h.id}>
              <SectionHeader title={h.daemonName} />
              {errored ? (
                <View className="flex-row items-center gap-3 px-4 py-3">
                  <Icon name="circle-alert" className="text-destructive" />
                  <View className="flex-1">
                    <Text>Unavailable</Text>
                    <Text variant="caption">
                      This host could not be reached.
                    </Text>
                  </View>
                </View>
              ) : null}
              {!errored && sessions.length === 0 && loading ? (
                <Text variant="muted" className="px-4 py-3">
                  Loading sessions
                </Text>
              ) : null}
              {sessions.map(s => (
                <SessionRow
                  key={s.sessionId}
                  title={s.title !== '' ? s.title : s.kind}
                  description={
                    s.preview !== ''
                      ? s.preview
                      : relativeAge(s.lastActivity, nowSec)
                  }
                  kind={s.kind}
                  running={s.running}
                  age={relativeAge(s.lastActivity, nowSec)}
                  hostName={h.daemonName}
                  onPress={() => openWorkspace(h)}
                />
              ))}
            </View>
          );
        })}

        {phase === 'ready' && failedHosts.length > 0 ? (
          <Notice
            tone="danger"
            message={`Unavailable: ${failedHosts
              .map(h => h.daemonName)
              .join(', ')}`}
            action={{ label: 'Retry', onPress: () => void load() }}
          />
        ) : null}
      </ScrollView>
    </Screen>
  );
}

interface SessionRowProps {
  title: string;
  description: string;
  kind: string;
  running: boolean;
  age: string;
  hostName: string;
  onPress: () => void;
}

function SessionRow({
  title,
  description,
  kind,
  running,
  age,
  hostName,
  onPress,
}: SessionRowProps): React.ReactElement {
  return (
    <Pressable
      role="button"
      accessibilityLabel={`${title} on ${hostName}, ${
        running ? 'running' : 'exited'
      }, ${age}`}
      onPress={onPress}
      className="flex-row items-center gap-3 px-4 py-3 active:bg-accent"
    >
      <Icon
        name={kind === 'shell' ? 'terminal' : 'bot'}
        className="text-muted-foreground"
      />
      <View className="flex-1 gap-0.5">
        <Text numberOfLines={2}>{title}</Text>
        <Text variant="caption" numberOfLines={2}>
          {description}
        </Text>
      </View>
      <StatusChip
        tone={running ? 'ok' : 'idle'}
        label={running ? age : 'Exited'}
      />
    </Pressable>
  );
}
