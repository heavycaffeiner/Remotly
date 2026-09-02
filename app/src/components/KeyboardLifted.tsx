// A container that stays clear of the software keyboard.
//
// android:windowSoftInputMode is adjustResize, but under edge-to-edge the
// window can keep its full height and the IME simply draws over it. A Modal is
// worse: it is its own window, so the activity resizing does not reach it at
// all. Both cases show as the field being typed into sitting behind the
// keyboard.
//
// Only the measured overlap is compensated. Where the platform really did
// resize, the keyboard's top edge sits at or below this view's bottom edge and
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

/**
 * How much of the view the keyboard currently covers, in pixels.
 *
 * The keyboard reports its top edge in screen coordinates, so the view's
 * bottom edge has to be in the same space to subtract them. Measuring gives
 * that; the height from onLayout does not, because it says nothing about where
 * the view starts. Using the height alone left the padding short by however
 * far down the screen the view began, which on a screen with a toolbar above
 * the terminal is the toolbar's height: enough rows stayed under the keyboard
 * to look like the terminal had not resized at all.
 */
export function useKeyboardOverlap(): {
  overlap: number;
  onLayout: (event: LayoutChangeEvent) => void;
  ref: React.RefObject<View | null>;
} {
  const ref = React.useRef<View | null>(null);
  const [overlap, setOverlap] = React.useState(0);
  // The keyboard's top edge, held so a later layout can recompute against it.
  const keyboardTop = React.useRef<number | null>(null);

  const measure = React.useCallback(() => {
    const top = keyboardTop.current;
    const node = ref.current;
    if (top === null || node === null) return;
    node.measureInWindow((_x, y, _w, h) => {
      const next = Math.max(0, Math.round(y + h - top));
      setOverlap(current => (current === next ? current : next));
    });
  }, []);

  // A layout pass moves the bottom edge, so the overlap is recomputed against
  // the keyboard that is already up.
  const onLayout = React.useCallback(
    (_event: LayoutChangeEvent) => measure(),
    [measure],
  );

  React.useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', (e: KeyboardEvent) => {
      keyboardTop.current = e.endCoordinates.screenY;
      measure();
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      keyboardTop.current = null;
      setOverlap(0);
    });
    return () => {
      show.remove();
      hide.remove();
    };
  }, [measure]);

  return { overlap, onLayout, ref };
}

export function KeyboardLifted({
  className,
  children,
}: KeyboardLiftedProps): React.ReactElement {
  const { overlap, onLayout, ref } = useKeyboardOverlap();
  return (
    <View
      ref={ref}
      onLayout={onLayout}
      style={{ paddingBottom: overlap }}
      className={className}
    >
      {children}
    </View>
  );
}
