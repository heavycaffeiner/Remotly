// The terminal session tab strip, shared by daemon workspaces and SSH hosts.
//
// One strip for both: a tab is a tab, and the caller supplies the list and the
// handlers. Selection is exposed through accessibilityState and the close
// action names its tab, so a screen reader user is never asked to close "tab".

import React, { useCallback } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import { cn } from '../../lib/utils';
import { IconButton } from '../../components/Screen';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Icon, type IconName } from '../../components/ui/icon';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';
import { Input } from '../../components/ui/input';
import { Text } from '../../components/ui/text';

/** How a tab is doing. Drives the icon shown beside its label. */
export type SessionTabStatus = 'live' | 'busy' | 'ended' | 'gone';

const STATUS_ICON: Record<SessionTabStatus, IconName | null> = {
  live: null,
  busy: 'clock',
  ended: 'circle-stop',
  gone: 'link-off',
};

const STATUS_SUFFIX: Record<SessionTabStatus, string> = {
  live: '',
  busy: ', connecting',
  ended: ', ended',
  gone: ', gone',
};

export interface SessionTabView {
  sessionId: string;
  label: string;
  status: SessionTabStatus;
  /** Overrides the status icon, for a tab that is not a shell. */
  icon?: IconName;
}

interface SessionTabsProps {
  tabs: readonly SessionTabView[];
  activeSessionId: string | null;
  onSelect: (sessionId: string) => void;
  onClose: (sessionId: string) => void;
  onNew: () => void;
  /**
   * Offered beside "New session" when the strip can hold more than one kind of
   * tab. With none supplied the plus button opens a session directly.
   */
  newKinds?: readonly {
    key: string;
    label: string;
    icon: IconName;
    onPress: () => void;
  }[];
  /**
   * Renames a tab. The daemon workspace sends the new name to the daemon,
   * which owns it; the SSH strip keeps it locally.
   */
  onRename?: (sessionId: string, title: string) => void;
  /**
   * Opens the rename dialog for the active tab when this changes.
   *
   * Lets a caller's menu item reuse this dialog instead of building a second
   * one that edits the same state.
   */
  renameRequest?: number;
  /** Disables the new-tab button when the host is at its limit. */
  canAdd?: boolean;
}

export function SessionTabs({
  tabs,
  activeSessionId,
  onSelect,
  onClose,
  onNew,
  newKinds,
  onRename,
  renameRequest = 0,
  canAdd = true,
}: SessionTabsProps): React.ReactElement {
  const [renaming, setRenaming] = React.useState<SessionTabView | null>(null);
  const [picking, setPicking] = React.useState(false);
  const [draft, setDraft] = React.useState('');

  const beginRename = useCallback((tab: SessionTabView) => {
    setRenaming(tab);
    setDraft(tab.label);
  }, []);

  // Zero is the initial value, not a request, so the dialog does not open on
  // mount.
  const active = tabs.find(t => t.sessionId === activeSessionId) ?? null;
  const activeRef = React.useRef(active);
  activeRef.current = active;
  React.useEffect(() => {
    if (renameRequest === 0) return;
    const tab = activeRef.current;
    if (tab !== null) beginRename(tab);
  }, [renameRequest, beginRename]);

  const commitRename = useCallback(() => {
    if (renaming !== null) onRename?.(renaming.sessionId, draft);
    setRenaming(null);
  }, [renaming, draft, onRename]);

  // Keeps the selected tab on screen. Switching by swipe or from the sessions
  // list moves the selection without touching the strip, so a tab off the
  // visible run stayed off it and the user could not see which one they were
  // on.
  const stripRef = React.useRef<ScrollView | null>(null);
  const layouts = React.useRef(new Map<string, { x: number; width: number }>());
  const stripWidth = React.useRef(0);
  const offset = React.useRef(0);

  React.useEffect(() => {
    if (activeSessionId === null) return;
    const at = layouts.current.get(activeSessionId);
    const view = stripRef.current;
    if (at === undefined || view === null) return;
    // Centred where there is room, so the neighbouring tabs stay visible and
    // the strip does not jump to an edge on every switch.
    const centred = at.x + at.width / 2 - stripWidth.current / 2;
    const x = Math.max(0, centred);
    offset.current = x;
    view.scrollTo({ x, animated: true });
  }, [activeSessionId, tabs.length]);

  // Closing a tab leaves the strip scrolled past the end of what remains, so
  // the content keeps its old width and scrolls into blank space. Dropping the
  // measurement and pulling the offset back in is what shrinks it.
  const openIds = tabs.map(t => t.sessionId).join('\u0000');
  React.useEffect(() => {
    const live = new Set(tabs.map(t => t.sessionId));
    for (const id of [...layouts.current.keys()]) {
      if (!live.has(id)) layouts.current.delete(id);
    }
  }, [openIds, tabs]);

  const onStripLayout = useCallback((width: number) => {
    stripWidth.current = width;
  }, []);

  const onTabLayout = useCallback(
    (sessionId: string, x: number, width: number) => {
      layouts.current.set(sessionId, { x, width });
    },
    [],
  );

  return (
    <View className="flex-row items-center border-b border-border bg-card">
      <ScrollView
        ref={stripRef}
        horizontal
        showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="always"
        contentContainerStyle={{ paddingHorizontal: 8, gap: 6 }}
        className="py-1.5"
        accessibilityRole="tablist"
        onLayout={e => onStripLayout(e.nativeEvent.layout.width)}
        scrollEventThrottle={16}
        onScroll={e => {
          offset.current = e.nativeEvent.contentOffset.x;
        }}
        // Closing a tab shrinks the content. The strip keeps the offset it had
        // while the wider content existed, which leaves it parked past the last
        // tab showing blank space, so an offset beyond the new end is pulled
        // back to it. Only when it is actually past: clamping unconditionally
        // would fight the scroll-to-active above and drag the strip to the end
        // on every switch.
        onContentSizeChange={contentWidth => {
          const max = Math.max(0, contentWidth - stripWidth.current);
          if (offset.current <= max) return;
          offset.current = max;
          stripRef.current?.scrollTo({ x: max, animated: true });
        }}
      >
        {tabs.map(tab => (
          <Tab
            key={tab.sessionId}
            tab={tab}
            active={tab.sessionId === activeSessionId}
            onSelect={onSelect}
            onClose={onClose}
            onLayout={onTabLayout}
            {...(onRename ? { onRename: beginRename } : {})}
          />
        ))}
      </ScrollView>
      <IconButton
        icon="plus"
        label={newKinds === undefined ? 'New session' : 'New tab'}
        disabled={!canAdd}
        onPress={newKinds === undefined ? onNew : () => setPicking(true)}
      />

      {newKinds === undefined ? null : (
        <Sheet open={picking} onClose={() => setPicking(false)}>
          <SheetContent>
            <SheetHeader>
              <SheetTitle>New tab</SheetTitle>
            </SheetHeader>
            {newKinds.map(k => (
              <Pressable
                key={k.key}
                role="button"
                accessibilityLabel={k.label}
                android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
                className="flex-row items-center gap-4 rounded-2xl px-4 py-3 active:bg-surface-variant/40"
                onPress={() => {
                  setPicking(false);
                  k.onPress();
                }}
              >
                <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
                  <Icon
                    name={k.icon}
                    size={20}
                    className="text-on-secondary-container"
                  />
                </View>
                <Text className="text-base font-medium text-foreground">
                  {k.label}
                </Text>
              </Pressable>
            ))}
          </SheetContent>
        </Sheet>
      )}

      <Dialog open={renaming !== null} onClose={() => setRenaming(null)}>
        <DialogHeader>
          <DialogTitle>Rename session</DialogTitle>
        </DialogHeader>
        <DialogContent>
          <Input
            value={draft}
            onChangeText={setDraft}
            accessibilityLabel="Session name"
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            onSubmitEditing={commitRename}
            returnKeyType="done"
          />
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" onPress={() => setRenaming(null)}>
            <Text>Cancel</Text>
          </Button>
          <Button disabled={draft.trim() === ''} onPress={commitRename}>
            <Text>Rename</Text>
          </Button>
        </DialogFooter>
      </Dialog>
    </View>
  );
}

interface TabProps {
  tab: SessionTabView;
  active: boolean;
  onSelect: (sessionId: string) => void;
  onClose: (sessionId: string) => void;
  /** Reports the tab's position in the strip, so the strip can scroll to it. */
  onLayout: (sessionId: string, x: number, width: number) => void;
  onRename?: (tab: SessionTabView) => void;
}

function Tab({
  tab,
  active,
  onSelect,
  onClose,
  onLayout,
  onRename,
}: TabProps): React.ReactElement {
  const select = useCallback(
    () => onSelect(tab.sessionId),
    [onSelect, tab.sessionId],
  );
  const close = useCallback(
    () => onClose(tab.sessionId),
    [onClose, tab.sessionId],
  );
  const rename = useCallback(() => onRename?.(tab), [onRename, tab]);
  const icon = tab.icon ?? STATUS_ICON[tab.status];
  const fg = active ? 'text-on-primary' : 'text-on-secondary-container';

  return (
    <View
      onLayout={e => {
        const { x, width } = e.nativeEvent.layout;
        onLayout(tab.sessionId, x, width);
      }}
      className={cn(
        'h-11 max-w-[220px] flex-row items-center rounded-full pl-3',
        active ? 'bg-primary' : 'bg-secondary-container',
      )}
    >
      <Pressable
        onPress={select}
        // Long press renames. The same action is on the terminal's overflow
        // menu, so this is a shortcut rather than the only way to reach it.
        {...(onRename ? { onLongPress: rename } : {})}
        accessibilityRole="tab"
        accessibilityState={{ selected: active }}
        accessibilityLabel={`${tab.label}${STATUS_SUFFIX[tab.status]}`}
        {...(onRename
          ? {
              accessibilityActions: [{ name: 'rename', label: 'Rename' }],
              onAccessibilityAction: (e: {
                nativeEvent: { actionName: string };
              }) => {
                if (e.nativeEvent.actionName === 'rename') rename();
              },
            }
          : {})}
        className="h-11 flex-shrink flex-row items-center gap-1.5"
      >
        {icon === null ? null : <Icon name={icon} size={13} className={fg} />}
        <Text
          numberOfLines={1}
          className={cn('flex-shrink text-sm font-medium', fg)}
        >
          {tab.label}
        </Text>
      </Pressable>
      <Pressable
        onPress={close}
        accessibilityRole="button"
        accessibilityLabel={`Close ${tab.label}`}
        className="h-11 w-9 items-center justify-center rounded-full"
      >
        <Icon name="x" size={15} className={fg} />
      </Pressable>
    </View>
  );
}
