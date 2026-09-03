// The new session picker.
//
// Only a plain shell and the presets the daemon itself advertises are offered.
// The app never assembles a command string of its own and sends it to a remote
// shell.

import React from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';
import { Icon } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import { cn } from '../../lib/utils';
import type { Preset } from '../../lib/sessions';

interface NewSessionSheetProps {
  visible: boolean;
  presets: readonly Preset[];
  busy: boolean;
  onDismiss: () => void;
  onCreate: (kind: 'shell' | 'agent', preset?: Preset) => void;
}

export function NewSessionSheet({
  visible,
  presets,
  busy,
  onDismiss,
  onCreate,
}: NewSessionSheetProps): React.ReactElement {
  // `busy` is the only guard against a double tap. It is owned by the screen,
  // which sets it around the create and clears it in a finally, so it is
  // false again for the next session. A local latch here was never cleared:
  // the dialog stays mounted across open and close, so after one session the
  // sheet refused every later tap and only one session could be created.
  const start = (kind: 'shell' | 'agent', preset?: Preset) => {
    if (busy) return;
    onCreate(kind, preset);
  };

  return (
    <Sheet open={visible} onClose={onDismiss}>
      <SheetHeader>
        <SheetTitle>New session</SheetTitle>
      </SheetHeader>
      <SheetContent className="gap-1 pb-6">
        <ScrollView className="max-h-80">
          <Row
            title="Shell"
            description="Your login shell, with your full environment."
            icon="terminal"
            disabled={busy}
            onPress={() => start('shell')}
          />
          {presets.map(p => (
            <Row
              key={p.name}
              title={p.name}
              icon="bot"
              disabled={busy}
              onPress={() => start('agent', p)}
            />
          ))}
        </ScrollView>
      </SheetContent>
    </Sheet>
  );
}

function Row({
  title,
  description,
  icon,
  disabled,
  onPress,
}: {
  title: string;
  description?: string;
  icon: 'terminal' | 'bot';
  disabled: boolean;
  onPress: () => void;
}): React.ReactElement {
  return (
    <Pressable
      role="button"
      accessibilityLabel={title}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
      className={cn(
        'min-h-14 flex-row items-center gap-4 rounded-2xl px-3 py-2.5 active:bg-surface-variant/40',
        disabled && 'opacity-40',
      )}
    >
      <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
        <Icon name={icon} size={20} className="text-on-secondary-container" />
      </View>
      <View className="flex-1 gap-0.5">
        <Text className="text-base font-medium">{title}</Text>
        {description === undefined ? null : (
          <Text variant="caption">{description}</Text>
        )}
      </View>
    </Pressable>
  );
}
