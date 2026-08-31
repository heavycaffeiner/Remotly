// The background transfer sheet.
//
// Transfers keep running when the browser is closed, so this is the one place
// that shows every one of them at once, whichever host or screen started it.

import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, View } from 'react-native';
import { Button } from '../../components/ui/button';
import { Icon } from '../../components/ui/icon';
import { Progress } from '../../components/ui/progress';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';
import { Text } from '../../components/ui/text';
import {
  canRetryTransfer,
  cancelTransfer,
  clearSettledTransfers,
  retryTransfer,
  subscribeTransfers,
  type TransferRecord,
} from '../../lib/transfers';
import { formatSize } from './filePresentation';

/** Live transfer list, for the sheet and the toolbar badge. */
export function useTransfers(): readonly TransferRecord[] {
  const [list, setList] = useState<readonly TransferRecord[]>([]);
  useEffect(() => subscribeTransfers(setList), []);
  return list;
}

/**
 * Whether the transfer sheet is showing.
 *
 * The sheet is mounted once, above the navigator, so it survives navigation
 * and cannot be drawn twice. Screens ask for it through [openTransferSheet]
 * rather than rendering their own: two mounted copies opened together and the
 * user saw one sheet stacked on the other.
 */
let sheetOpen = false;
const sheetListeners = new Set<(open: boolean) => void>();

function setSheetOpen(next: boolean): void {
  if (sheetOpen === next) return;
  sheetOpen = next;
  for (const l of sheetListeners) l(next);
}

/** Opens the app-wide transfer sheet from anywhere. */
export function openTransferSheet(): void {
  setSheetOpen(true);
}

function useSheetOpen(): boolean {
  const [open, setOpen] = useState(sheetOpen);
  useEffect(() => {
    sheetListeners.add(setOpen);
    setOpen(sheetOpen);
    return () => {
      sheetListeners.delete(setOpen);
    };
  }, []);
  return open;
}

/**
 * The app-wide transfer sheet. Mounted once, above the navigator.
 */
export function TransferSheet(): React.ReactElement {
  const open = useSheetOpen();
  const onClose = useCallback(() => setSheetOpen(false), []);
  const list = useTransfers();
  const hasSettled = list.some(t => t.phase !== 'active');
  // Reported inside the sheet's own window: a Toast on the screen behind it
  // cannot draw over a Modal, so acting from here used to give no feedback.
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (notice === '') return undefined;
    const timer = setTimeout(() => setNotice(''), 2000);
    return () => clearTimeout(timer);
  }, [notice]);

  const clearFinished = useCallback(() => {
    clearSettledTransfers();
    setNotice('Cleared finished transfers');
  }, []);

  return (
    <Sheet open={open} onClose={onClose} toast={notice}>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>Transfers</SheetTitle>
        </SheetHeader>

        {list.length === 0 ? (
          <Text className="py-6 text-center text-sm text-muted-foreground">
            Nothing transferring
          </Text>
        ) : (
          // The sheet already caps its own height, so the list is left to
          // shrink inside it. A fixed cap here fought that: on a short screen
          // the list kept its height and the Clear button was pushed off.
          <ScrollView
            className="shrink"
            contentContainerStyle={{ paddingBottom: 4 }}
          >
            <View className="gap-3">
              {list.map(t => (
                <TransferRow key={t.id} record={t} onNotice={setNotice} />
              ))}
            </View>
          </ScrollView>
        )}

        {hasSettled ? (
          <Button
            variant="ghost"
            size="sm"
            className="mt-3 self-end"
            onPress={clearFinished}
          >
            <Text>Clear finished</Text>
          </Button>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function TransferRow({
  record,
  onNotice,
}: {
  record: TransferRecord;
  onNotice: (message: string) => void;
}): React.ReactElement {
  const cancel = useCallback(() => {
    cancelTransfer(record.id);
    onNotice(`Cancelled ${record.name}`);
  }, [record.id, record.name, onNotice]);
  // The button reads Resume or Retry depending on what the backend can do, so
  // the confirmation has to say the same thing rather than always "restarted".
  const retry = useCallback(() => {
    const continuing = record.resumable === true && record.transferred > 0;
    retryTransfer(record.id);
    onNotice(
      continuing ? `Resumed ${record.name}` : `Restarted ${record.name}`,
    );
  }, [record.id, record.name, record.resumable, record.transferred, onNotice]);
  const known = record.total > 0;
  // Offered for anything that stopped short. Resume continues from what
  // already moved; where the backend cannot do that the same button starts
  // over, and says so rather than implying bytes are kept.
  const stopped = record.phase === 'error' || record.phase === 'cancelled';
  const canPickUp = stopped && canRetryTransfer(record.id);
  const resumes = record.resumable === true && record.transferred > 0;

  return (
    <View className="gap-1.5">
      <View className="flex-row items-center gap-2">
        <Icon
          name={record.direction === 'upload' ? 'arrow-up' : 'arrow-down'}
          size={16}
          className="text-muted-foreground"
        />
        <Text className="flex-1 text-sm text-foreground" numberOfLines={1}>
          {record.name}
        </Text>
        {record.phase === 'active' ? (
          <Button
            variant="ghost"
            size="sm"
            accessibilityLabel={`Cancel ${record.name}`}
            onPress={cancel}
          >
            <Text>Cancel</Text>
          </Button>
        ) : canPickUp ? (
          <Button
            variant="ghost"
            size="sm"
            accessibilityLabel={`${resumes ? 'Resume' : 'Retry'} ${
              record.name
            }`}
            onPress={retry}
          >
            <Text>{resumes ? 'Resume' : 'Retry'}</Text>
          </Button>
        ) : null}
      </View>

      {record.phase === 'active' ? (
        <Progress
          label={`${record.name} progress`}
          {...(known ? { value: record.transferred / record.total } : {})}
        />
      ) : null}

      <Text className="text-xs text-muted-foreground">
        {statusLine(record)}
      </Text>
    </View>
  );
}

function statusLine(t: TransferRecord): string {
  switch (t.phase) {
    case 'active':
      return t.total > 0
        ? `${formatSize(t.transferred)} of ${formatSize(t.total)}`
        : formatSize(t.transferred);
    case 'done':
      return `Finished, ${formatSize(t.transferred)}`;
    case 'cancelled':
      return t.resumable === true && t.transferred > 0
        ? `Cancelled at ${formatSize(t.transferred)}`
        : 'Cancelled';
    case 'error':
      return t.error ?? 'Failed';
  }
}
