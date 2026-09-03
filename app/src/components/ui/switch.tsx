import * as React from 'react';
import { Animated, Pressable, View } from 'react-native';
import { cn } from '../../lib/utils';

interface SwitchProps {
  value: boolean;
  onValueChange?: (value: boolean) => void;
  disabled?: boolean;
  accessibilityLabel?: string;
  className?: string;
}

/**
 * Material Design 3 Switch.
 *
 * 52x32dp pill track with an animated thumb that transitions from 16dp (off)
 * to 24dp (on) as it translates across the track.
 */
function Switch({
  value,
  onValueChange,
  disabled = false,
  accessibilityLabel,
  className,
}: SwitchProps): React.ReactElement {
  const anim = React.useRef(new Animated.Value(value ? 1 : 0)).current;

  React.useEffect(() => {
    Animated.timing(anim, {
      toValue: value ? 1 : 0,
      duration: 180,
      useNativeDriver: false,
    }).start();
  }, [value, anim]);

  const toggle = React.useCallback(() => {
    if (!disabled) {
      onValueChange?.(!value);
    }
  }, [disabled, onValueChange, value]);

  const translateX = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [4, 24],
  });

  const thumbSize = anim.interpolate({
    inputRange: [0, 1],
    outputRange: [16, 24],
  });

  return (
    <Pressable
      role="switch"
      accessibilityRole="switch"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      onPress={toggle}
      hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
      className={cn('justify-center py-2', disabled && 'opacity-38', className)}
    >
      <View
        className={cn(
          'h-8 w-[52px] rounded-full justify-center',
          value
            ? 'bg-primary'
            : 'bg-surface-container-highest border-2 border-outline',
        )}
      >
        <Animated.View
          style={{
            transform: [{ translateX }],
            width: thumbSize,
            height: thumbSize,
          }}
          className={cn(
            'rounded-full',
            value ? 'bg-primary-foreground' : 'bg-outline',
          )}
        />
      </View>
    </Pressable>
  );
}

export { Switch };
