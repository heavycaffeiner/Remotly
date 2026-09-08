import * as React from 'react';
import { Dialog as PaperDialog, Portal } from 'react-native-paper';
import { useKeyboardHeight } from '../KeyboardLifted';
import { Text } from './text';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  /** Disallows dismissing by tapping outside or with the back button. */
  dismissable?: boolean;
  children: React.ReactNode;
}
function Dialog({
  open,
  onClose,
  dismissable = true,
  children,
}: DialogProps): React.ReactElement {
  // Paper's Dialog has no keyboard handling of its own, and a Portal puts it
  // outside the screen tree, so a dialog with a text field sits under the IME.
  // The margin shrinks the centred box from the bottom, which re-centres the
  // dialog in the space that is left.
  const keyboard = useKeyboardHeight();
  return (
    <Portal>
      <PaperDialog
        visible={open}
        onDismiss={onClose}
        dismissable={dismissable}
        dismissableBackButton={dismissable}
        style={{ marginBottom: keyboard }}
      >
        {children}
      </PaperDialog>
    </Portal>
  );
}

// Transparent: Paper lays the dialog out from its own Title/Content/Actions
// children, so the header must not introduce a wrapper between them.
function DialogHeader({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return <>{children}</>;
}

function DialogTitle(
  props: React.ComponentProps<typeof PaperDialog.Title>,
): React.ReactElement {
  return <PaperDialog.Title {...props} />;
}

function DialogDescription(
  props: React.ComponentProps<typeof Text>,
): React.ReactElement {
  return <Text variant="muted" {...props} />;
}

function DialogContent(
  props: React.ComponentProps<typeof PaperDialog.Content>,
): React.ReactElement {
  return <PaperDialog.Content {...props} />;
}

function DialogFooter(
  props: React.ComponentProps<typeof PaperDialog.Actions>,
): React.ReactElement {
  return <PaperDialog.Actions {...props} />;
}

export {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
};
