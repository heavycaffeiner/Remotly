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
      placeholderClassName="text-muted-foreground"
      className={cn(
        'h-12 rounded-lg border bg-surface px-4 text-base text-foreground',
        invalid ? 'border-2 border-destructive' : 'border-outline/50',
        props.editable === false && 'opacity-38',
        className,
      )}
      {...props}
    />
  );
});

export { Input };
