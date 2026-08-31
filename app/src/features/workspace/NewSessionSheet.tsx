// The new session picker.
//
// Only a plain shell and the presets the daemon itself advertises are offered.
// The app never assembles a command string of its own and sends it to a remote
// shell.

import React, { useState } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Button } from '../../components/ui/button';
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
  const [pending, setPending] = useState<string | null>(null);
  const locked = busy || pending !== null;

  const start = (kind: 'shell' | 'agent', preset?: Preset) => {
    if (locked) return;
    setPending(preset?.name ?? 'shell');
    onCreate(kind, preset);
  };

  return (
    <Dialog open={visible} onClose={onDismiss} dismissable={!busy}>
      <DialogHeader>
        <DialogTitle>New session</DialogTitle>
      </DialogHeader>
      <DialogContent>
        <ScrollView className="max-h-80">
          <Row
            title="Shell"
            description="Your login shell, with your full environment."
            icon="terminal"
            disabled={locked}
            onPress={() => start('shell')}
          />
          {presets.map(p => (
            <Row
              key={p.name}
              title={p.name}
              icon="bot"
              disabled={locked}
              onPress={() => start('agent', p)}
            />
          ))}
        </ScrollView>
      </DialogContent>
      <DialogFooter>
        <Button variant="ghost" disabled={busy} onPress={onDismiss}>
          <Text>Cancel</Text>
        </Button>
      </DialogFooter>
    </Dialog>
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
      className={cn(
        'min-h-14 flex-row items-center gap-3 rounded-md px-3 py-2 active:bg-accent',
        disabled && 'opacity-50',
      )}
    >
      <Icon name={icon} className="text-muted-foreground" />
      <View className="flex-1 gap-0.5">
        <Text>{title}</Text>
        {description === undefined ? null : (
          <Text variant="caption">{description}</Text>
        )}
      </View>
    </Pressable>
  );
}
