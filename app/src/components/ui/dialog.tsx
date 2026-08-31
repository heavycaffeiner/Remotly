import * as React from 'react';
import { Modal, Pressable, View, type ViewProps } from 'react-native';
import { KeyboardLifted } from '../KeyboardLifted';
import { cn } from '../../lib/utils';
import { Text } from './text';

interface DialogProps {
  open: boolean;
  /** Called for a back-button press or a scrim tap. */
  onClose: () => void;
  /**
   * Blocks dismissal. Used where the user must make an explicit choice, such
   * as a host key prompt, so there is no path that continues by accident.
   */
  dismissable?: boolean;
  children: React.ReactNode;
}

/**
 * A modal dialog.
 *
 * Built on RN's Modal rather than a portal, so Android's back button and the
 * platform's own focus trapping apply without reimplementing either.
 */
function Dialog({
  open,
  onClose,
  dismissable = true,
  children,
}: DialogProps): React.ReactElement {
  return (
    <Modal
      visible={open}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={dismissable ? onClose : undefined}
    >
      {/* Several dialogs hold a text field (rename, new folder, the pairing
          code). A Modal is its own window, so the screen behind it resizing
          for the keyboard does nothing here and the IME drew straight over
          the field. Lifting by the measured overlap keeps it visible. */}
      <KeyboardLifted className="flex-1 items-center justify-center bg-black/50 p-6">
        {dismissable ? (
          <Pressable
            className="absolute inset-0"
            accessibilityLabel="Dismiss"
            onPress={onClose}
          />
        ) : null}
        <View className="w-full max-w-md rounded-lg border border-border bg-popover">
          {children}
        </View>
      </KeyboardLifted>
    </Modal>
  );
}

function DialogHeader({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-2 p-5 pb-3', className)} {...props} />;
}

function DialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return (
    <Text
      role="heading"
      variant="title"
      className={cn('text-popover-foreground', className)}
      {...props}
    />
  );
}

function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return <Text variant="muted" className={className} {...props} />;
}

function DialogContent({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-3 px-5 pb-3', className)} {...props} />;
}

function DialogFooter({ className, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      className={cn('flex-row justify-end gap-2 p-5 pt-3', className)}
      {...props}
    />
  );
}

export {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
};
