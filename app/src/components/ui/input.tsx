import * as React from 'react';
import { TextInput as PaperTextInput } from 'react-native-paper';
import type { TextInputHandles, TextInputProps } from 'react-native-paper';

type InputProps = Omit<TextInputProps, 'variant' | 'error'> & {
  /** Draws the error outline and marks the field invalid for assistive tech. */
  invalid?: boolean;
};

const Input = React.forwardRef<TextInputHandles, InputProps>(
  function InputField({ invalid = false, editable, ...props }, ref) {
    return (
      <PaperTextInput
        ref={ref}
        variant="outlined"
        error={invalid}
        editable={editable}
        disabled={editable === false}
        {...props}
      />
    );
  },
);

export { Input };
