import * as React from 'react';
import { Modal, Pressable, View, type ViewProps } from 'react-native';
import { KeyboardLifted } from '../KeyboardLifted';
import { cn } from '../../lib/utils';
import { Text } from './text';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  /** Disallows dismissing by tapping outside or with the back button. */
  dismissable?: boolean;
  children: React.ReactNode;
}

/**
 * A modal dialog following Material Design 3 specifications (28dp radius, no border).
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
      <KeyboardLifted className="flex-1 items-center justify-center bg-black/60 p-6">
        {dismissable ? (
          <Pressable
            className="absolute inset-0"
            accessibilityLabel="Dismiss"
            onPress={onClose}
          />
        ) : null}
        <View className="w-full max-w-md rounded-[28px] bg-popover shadow-xl overflow-hidden">
          {children}
        </View>
      </KeyboardLifted>
    </Modal>
  );
}

function DialogHeader({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-3 p-6 pb-4', className)} {...props} />;
}

function DialogTitle({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return (
    <Text
      role="heading"
      variant="h3"
      className={cn(
        'text-2xl font-normal text-foreground tracking-tight',
        className,
      )}
      {...props}
    />
  );
}

function DialogDescription({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return (
    <Text
      variant="muted"
      className={cn('text-sm leading-relaxed', className)}
      {...props}
    />
  );
}

function DialogContent({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-4 px-6 pb-4', className)} {...props} />;
}

function DialogFooter({ className, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      className={cn('flex-row justify-end gap-2 px-6 pb-6 pt-2', className)}
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
