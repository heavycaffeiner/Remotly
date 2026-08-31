import * as React from 'react';
import { TextInput } from 'react-native';
import { cn } from '../../lib/utils';

type InputProps = React.ComponentProps<typeof TextInput> & {
  /** Draws the error ring and marks the field invalid for assistive tech. */
  invalid?: boolean;
};

const Input = React.forwardRef<
  React.ComponentRef<typeof TextInput>,
  InputProps
>(function InputField({ className, invalid = false, ...props }, ref) {
  return (
    <TextInput
      ref={ref}
      accessibilityState={{ disabled: props.editable === false }}
      // The placeholder is not a label. Callers pass an explicit
      // accessibilityLabel; this only keeps the color readable.
      placeholderClassName="text-muted-foreground"
      className={cn(
        'h-12 rounded-md border bg-background px-3 text-base text-foreground',
        invalid ? 'border-destructive' : 'border-input',
        props.editable === false && 'opacity-50',
        className,
      )}
      {...props}
    />
  );
});

export { Input };
