// A container that stays clear of the software keyboard.
//
// android:windowSoftInputMode is adjustResize, but under edge-to-edge the
// window can keep its full height and the IME simply draws over it. A Modal is
// worse: it is its own window, so the activity resizing does not reach it at
// all. Both cases show as the field being typed into sitting behind the
// keyboard.
//
// Only the measured overlap is compensated. Where the platform really did
// resize, endCoordinates.screenY sits at or below this view's bottom edge and
// the padding stays zero, so it is never applied twice.

import * as React from 'react';
import {
  Keyboard,
  type KeyboardEvent,
  type LayoutChangeEvent,
  View,
} from 'react-native';

interface KeyboardLiftedProps {
  className?: string;
  children: React.ReactNode;
}

/** How much of the view the keyboard currently covers, in pixels. */
export function useKeyboardOverlap(): {
  overlap: number;
  onLayout: (event: LayoutChangeEvent) => void;
} {
  const [height, setHeight] = React.useState(0);
  const [overlap, setOverlap] = React.useState(0);

  const onLayout = React.useCallback((event: LayoutChangeEvent) => {
    // React Native recycles the synthetic event once this returns, so the
    // value is read here rather than inside the state updater, which can run
    // later.
    const next = Math.round(event.nativeEvent.layout.height);
    setHeight(current => (current === next ? current : next));
  }, []);

  React.useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', (e: KeyboardEvent) => {
      setOverlap(Math.max(0, Math.round(height - e.endCoordinates.screenY)));
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => setOverlap(0));
    return () => {
      show.remove();
      hide.remove();
    };
  }, [height]);

  return { overlap, onLayout };
}

export function KeyboardLifted({
  className,
  children,
}: KeyboardLiftedProps): React.ReactElement {
  const { overlap, onLayout } = useKeyboardOverlap();
  return (
    <View
      onLayout={onLayout}
      style={{ paddingBottom: overlap }}
      className={className}
    >
      {children}
    </View>
  );
}
