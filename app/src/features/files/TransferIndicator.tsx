// The app-wide transfer indicator.
//
// Transfers keep running when the browser is closed, so the queue has to be
// reachable from wherever the user happens to be. This is a compact bar that
// appears only while something is moving and opens the full sheet.

import React, { useCallback, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Icon } from '../../components/ui/icon';
import { Progress } from '../../components/ui/progress';
import { Text } from '../../components/ui/text';
import { TransferSheet, useTransfers } from './TransferSheet';
import { raisesTransferBar } from '../../lib/transfers';
import { formatSize } from './filePresentation';

/**
 * Height of the shell's navigation bar, in pixels.
 *
 * The indicator floats over the whole stack, so it cannot measure the bar
 * beneath it. Sitting on top of the tabs would cover them; this keeps it just
 * above. Screens without a tab bar show it a little higher than strictly
 * needed, which reads as a margin rather than a defect.
 */
export const TAB_BAR_HEIGHT = 56;

/**
 * The indicator's own height, in pixels.
 *
 * Its bottom edge sits at insets.bottom + TAB_BAR_HEIGHT and it extends
 * upward from there, so anything else pinned to that edge has to clear both
 * numbers. Exported for the toast, which does exactly that.
 */
export const INDICATOR_HEIGHT = 50;

export function TransferIndicator(): React.ReactElement | null {
  const list = useTransfers();
  const insets = useSafeAreaInsets();
  const [open, setOpen] = useState(false);
  const show = useCallback(() => setOpen(true), []);
  const hide = useCallback(() => setOpen(false), []);

  const active = list.filter(t => t.phase === 'active');
  // Anything that stopped short is worth surfacing too: it is the state a user
  // would otherwise only discover by opening the sheet on the off chance.
  const failed = list.filter(t => t.phase === 'error').length;

  // The same predicate the toast reads to lift itself clear of this bar. Two
  // hand-written copies of the condition would let them disagree, and the
  // toast would then be drawn underneath.
  if (!list.some(raisesTransferBar)) return null;

  const running = active.length;
  const moved = active.reduce((n, t) => n + t.transferred, 0);
  const total = active.reduce((n, t) => (t.total > 0 ? n + t.total : n), 0);
  const fraction = total > 0 ? Math.min(1, moved / total) : null;

  const label =
    running > 0
      ? `${running} transfer${running === 1 ? '' : 's'} running`
      : `${failed} transfer${failed === 1 ? '' : 's'} failed`;

  return (
    <>
      <Pressable
        role="button"
        accessibilityLabel={`${label}. Open transfers.`}
        onPress={show}
        // Floated over the stack rather than laid out inside a screen, so it
        // survives navigation. Kept clear of the gesture bar, and of the tab
        // bar the main shell draws there.
        style={{ bottom: insets.bottom + TAB_BAR_HEIGHT }}
        className="absolute inset-x-0 z-10 border-t border-border bg-card px-4 py-2 active:bg-accent"
      >
        <View className="flex-row items-center gap-2">
          <Icon
            name={running > 0 ? 'arrow-down-up' : 'circle-alert'}
            size={16}
            className={
              running > 0 ? 'text-muted-foreground' : 'text-destructive'
            }
          />
          <Text className="flex-1 text-sm text-foreground" numberOfLines={1}>
            {label}
          </Text>
          {running > 0 && total > 0 ? (
            <Text className="text-xs text-muted-foreground">
              {`${formatSize(moved)} / ${formatSize(total)}`}
            </Text>
          ) : null}
        </View>
        {running > 0 ? (
          <Progress
            label="Overall transfer progress"
            className="mt-1.5"
            {...(fraction === null ? {} : { value: fraction })}
          />
        ) : null}
      </Pressable>
      <TransferSheet open={open} onClose={hide} />
    </>
  );
}
