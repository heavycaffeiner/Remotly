import * as React from 'react';
import { FAB as PaperFAB } from 'react-native-paper';

type FabProps = React.ComponentProps<typeof PaperFAB>;

// The radius is a shape token rather than a style: the button animates its own
// corner, and a style borderRadius is overwritten by it. 28 is half of the
// 56dp container, so the square becomes a circle.
const ROUND = { shapes: { corner: { large: 28 } } };

/**
 * A round floating action button.
 *
 * Material draws this one as a rounded square, which reads as another panel
 * over screens that are already all rectangles.
 */
function Fab(props: FabProps): React.ReactElement {
  return <PaperFAB theme={ROUND} {...props} />;
}

export { Fab };
