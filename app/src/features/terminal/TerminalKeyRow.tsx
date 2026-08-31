// The extra terminal key row.
//
// One row, scrolled horizontally. Wrapping to a second row changed the
// terminal's height and made the remote PTY resize unpredictably, so the row
// height is fixed and overflow scrolls instead.
//
// The active modifier is owned by the parent. Latching it here meant the
// visual state could stay selected after the parent had already consumed and
// cleared the latch.

import React, { useCallback, useEffect, useRef } from 'react';
import { Pressable, ScrollView, Vibration, View } from 'react-native';
import { cn } from '../../lib/utils';
import { Icon, type IconName } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import { KeyRepeater } from './keyRepeat';
import type { ModifierKey } from './terminalInput';

/** Vibration for one key press, short enough not to feel like an alert. */
const KEY_HAPTIC_MS = 12;

/**
 * How long a touch waits before it counts as a key press.
 *
 * A horizontal drag is recognised as a scroll within this window, so the key
 * under the finger never fires. Below roughly 60ms the scroll has not been
 * claimed yet; above roughly 120ms a deliberate tap starts to feel sticky.
 */
const PRESS_DELAY_MS = 80;

/** Keeps the platform long-press well clear of the press delay above. */
const LONG_PRESS_MS = 500;

interface KeyDef {
  key: string;
  label: string;
  text?: string;
  icon?: IconName;
  modifier?: ModifierKey;
  /** Holding this key streams it, the way a physical keyboard does. */
  repeats?: boolean;
}

const KEYS: readonly KeyDef[] = [
  { key: 'esc', label: 'Escape', text: 'Esc' },
  { key: 'tab', label: 'Tab', text: 'Tab' },
  { key: 'ctrl', label: 'Control', text: 'Ctrl', modifier: 'ctrl' },
  { key: 'alt', label: 'Alt', text: 'Alt', modifier: 'alt' },
  { key: 'slash', label: 'Slash', text: '/' },
  { key: 'pipe', label: 'Pipe', text: '|' },
  { key: 'backslash', label: 'Backslash', text: '\\' },
  { key: 'left', label: 'Arrow left', icon: 'arrow-left', repeats: true },
  { key: 'down', label: 'Arrow down', icon: 'arrow-down', repeats: true },
  { key: 'up', label: 'Arrow up', icon: 'arrow-up', repeats: true },
  { key: 'right', label: 'Arrow right', icon: 'arrow-right', repeats: true },
  { key: 'home', label: 'Home', text: 'Home' },
  { key: 'end', label: 'End', text: 'End' },
  { key: 'pageup', label: 'Page up', text: 'PgUp', repeats: true },
  { key: 'pagedown', label: 'Page down', text: 'PgDn', repeats: true },
];

interface TerminalKeyRowProps {
  /** A non-modifier key press, by logical key name. */
  onKey: (key: string) => void;
  /** A modifier press. The parent decides whether to latch or clear it. */
  onModifier: (modifier: ModifierKey) => void;
  /** The modifier currently latched by the parent, if any. */
  activeModifier: ModifierKey | null;
  /** Delay before a held key starts repeating. */
  repeatDelayMs: number;
  /** Vibrate on each key. */
  haptics: boolean;
}

export function TerminalKeyRow({
  onKey,
  onModifier,
  activeModifier,
  repeatDelayMs,
  haptics,
}: TerminalKeyRowProps): React.ReactElement {
  // One repeater for the whole row. A per-key repeater cannot enforce that
  // only one key is held: a finger sliding from one key to the next, or the
  // scroll view claiming the touch, leaves the first key's release unfired and
  // two streams running at once.
  const onKeyRef = useRef(onKey);
  onKeyRef.current = onKey;

  const repeater = useRef<KeyRepeater | null>(null);
  if (repeater.current === null) {
    repeater.current = new KeyRepeater(
      k => onKeyRef.current(k),
      setTimeout,
      clearTimeout,
      repeatDelayMs,
    );
  }
  useEffect(() => {
    repeater.current?.setDelay(repeatDelayMs);
  }, [repeatDelayMs]);

  // A row unmounted mid-hold would otherwise keep firing into a dead screen.
  useEffect(() => {
    const held = repeater.current;
    return () => held?.stop();
  }, []);

  const press = useCallback(
    (def: KeyDef) => {
      // Vibrating needs the VIBRATE permission. It is declared, but a key row
      // must not take the app down if a device or profile withholds it.
      if (haptics) {
        try {
          Vibration.vibrate(KEY_HAPTIC_MS);
        } catch {
          // Feedback is a nicety; the key press itself still goes through.
        }
      }
      if (def.modifier !== undefined) {
        repeater.current?.stop();
        onModifier(def.modifier);
        return;
      }
      if (def.repeats === true) repeater.current?.press(def.key);
      else onKey(def.key);
    },
    [haptics, onKey, onModifier],
  );

  const release = useCallback((def: KeyDef) => {
    if (def.repeats === true) repeater.current?.release(def.key);
  }, []);

  // True while the row is being scrolled. A key fires on press-in so a held
  // key can start repeating, which also meant the touch that begins a scroll
  // fired one first. The row reports its own drag, and a key that sees one is
  // cancelled rather than sent.
  const scrolling = useRef(false);
  const beginScroll = useCallback(() => {
    scrolling.current = true;
    // A drag that starts on a held key ends that key's stream.
    repeater.current?.stop();
  }, []);
  const endScroll = useCallback(() => {
    scrolling.current = false;
  }, []);

  return (
    <View className="border-t border-border bg-card">
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="always"
        contentContainerStyle={{ paddingHorizontal: 8, gap: 6 }}
        className="py-1.5"
        onScrollBeginDrag={beginScroll}
        onScrollEndDrag={endScroll}
        onMomentumScrollEnd={endScroll}
      >
        {KEYS.map(k => (
          <KeyButton
            key={k.key}
            def={k}
            active={k.modifier !== undefined && k.modifier === activeModifier}
            onPress={press}
            onRelease={release}
            isScrolling={scrolling}
          />
        ))}
      </ScrollView>
    </View>
  );
}

interface KeyButtonProps {
  def: KeyDef;
  active: boolean;
  onPress: (def: KeyDef) => void;
  onRelease: (def: KeyDef) => void;
  /** Set while the row is being dragged, so a scroll does not press a key. */
  isScrolling: React.RefObject<boolean>;
}

function KeyButton({
  def,
  active,
  onPress,
  onRelease,
  isScrolling,
}: KeyButtonProps): React.ReactElement {
  // Whether this touch actually pressed the key, so a scroll that starts on it
  // does not release a key that never fired.
  const fired = useRef(false);

  // Press-in rather than press, so a held key starts streaming while the
  // finger is still down. onPress only fires on release.
  const pressIn = useCallback(() => {
    if (isScrolling.current) {
      fired.current = false;
      return;
    }
    fired.current = true;
    onPress(def);
  }, [def, onPress, isScrolling]);
  const pressOut = useCallback(() => {
    if (!fired.current) return;
    fired.current = false;
    onRelease(def);
  }, [def, onRelease]);

  return (
    <Pressable
      role="button"
      accessibilityLabel={def.label}
      // Long enough for a drag to register as a scroll before the key fires,
      // short enough that a deliberate tap still feels immediate. Without it
      // press-in wins the race against onScrollBeginDrag and every scroll
      // sends a keystroke.
      delayLongPress={LONG_PRESS_MS}
      unstable_pressDelay={PRESS_DELAY_MS}
      // A latched modifier reports as selected rather than only looking it.
      accessibilityState={
        def.modifier === undefined ? undefined : { selected: active }
      }
      onPressIn={pressIn}
      onPressOut={pressOut}
      className={cn(
        'h-11 min-w-11 items-center justify-center rounded-md border px-3',
        active
          ? 'border-primary bg-primary'
          : 'border-border bg-secondary active:bg-accent',
      )}
    >
      {def.icon === undefined ? (
        <Text
          className={cn(
            'text-sm font-medium',
            active ? 'text-primary-foreground' : 'text-secondary-foreground',
          )}
        >
          {def.text ?? def.label}
        </Text>
      ) : (
        <Icon
          name={def.icon}
          size={18}
          className={
            active ? 'text-primary-foreground' : 'text-secondary-foreground'
          }
        />
      )}
    </Pressable>
  );
}
