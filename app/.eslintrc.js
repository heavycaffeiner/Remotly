module.exports = {
  root: true,
  extends: '@react-native',
  rules: {
    // `void promise` is the house pattern for a deliberate fire-and-forget
    // call whose failure is already handled inside the promise. Writing it
    // marks the omission as intentional, which is the opposite of what this
    // rule assumes.
    'no-void': 'off',

    // Styling is Tailwind class names, so a style prop is now the exception
    // rather than the rule: safe-area insets, measured keyboard offsets, and
    // computed widths, none of which a class name can express.
    'react-native/no-inline-styles': 'off',
  },
  overrides: [
    {
      // Byte codecs and wire framing. Bitwise operators are the correct and
      // clearest way to express these, and rewriting them with arithmetic
      // would obscure the format.
      files: [
        'src/lib/base64.ts',
        'src/lib/base64url.ts',
        'src/lib/pairing.ts',
        'src/lib/daemonTransfer.ts',
        'src/features/terminal/terminalInput.ts',
        'src/lib/__tests__/base64.test.ts',
        'src/lib/__tests__/base64url.test.ts',
        'src/lib/__tests__/pairing.test.ts',
        'src/lib/__tests__/daemonTransfer.test.ts',
        'src/features/terminal/__tests__/terminalInput.test.ts',
      ],
      rules: {
        'no-bitwise': 'off',
      },
    },
    {
      // FlatList takes render props for its header, empty, and footer slots. A
      // function passed there is not a component definition, but the rule
      // cannot tell the difference. Where a real state-loss risk existed, in
      // the virtualized file list, the row is defined at module scope; see
      // FileListItem.tsx.
      files: ['src/features/**/*.tsx', 'src/components/**/*.tsx'],
      rules: {
        'react/no-unstable-nested-components': ['warn', { allowAsProps: true }],
      },
    },
  ],
};
