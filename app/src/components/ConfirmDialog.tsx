// A yes/no confirmation.
//
// The destructive variant names what is being destroyed in its message; the
// button label says the verb, never just "OK", so the choice is legible from
// the button alone.

import * as React from 'react';
import { ActivityIndicator } from 'react-native';
import { Button } from './ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import { Text } from './ui/text';

interface ConfirmDialogProps {
  visible: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  /** Renders the confirm button in the destructive color. */
  destructive?: boolean;
  /** Disables both buttons and shows progress while the action runs. */
  busy?: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}

export function ConfirmDialog({
  visible,
  title,
  message,
  confirmLabel,
  cancelLabel = 'Cancel',
  destructive = false,
  busy = false,
  onConfirm,
  onDismiss,
}: ConfirmDialogProps): React.ReactElement {
  return (
    // Not dismissable while the action is in flight: dismissing then would
    // leave the caller running work with no dialog to report back to.
    <Dialog open={visible} onClose={onDismiss} dismissable={!busy}>
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
      </DialogHeader>
      <DialogContent>
        <DialogDescription>{message}</DialogDescription>
      </DialogContent>
      <DialogFooter>
        <Button variant="ghost" disabled={busy} onPress={onDismiss}>
          <Text>{cancelLabel}</Text>
        </Button>
        <Button
          variant={destructive ? 'destructive' : 'default'}
          disabled={busy}
          onPress={onConfirm}
        >
          {busy ? <ActivityIndicator size="small" /> : null}
          <Text>{confirmLabel}</Text>
        </Button>
      </DialogFooter>
    </Dialog>
  );
}
