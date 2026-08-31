module.exports = {
  preset: '@react-native/jest-preset',
  // The RN preset's transform covers js/ts/tsx only. lucide-react-native
  // resolves to ESM .mjs under the react-native export condition, so babel has
  // to see that extension too.
  transform: {
    '^.+\\.(js|jsx|mjs|ts|tsx)$': 'babel-jest',
  },
  moduleFileExtensions: ['js', 'jsx', 'mjs', 'ts', 'tsx', 'json', 'node'],
  // Metro compiles the stylesheet; under Jest the import is a no-op, since
  // these tests assert behavior and never computed styles.
  moduleNameMapper: {
    '\\.css$': '<rootDir>/jest.css-stub.js',
  },
  // @react-navigation ships ESM-only modules, and nativewind's interop layer
  // ships untransformed JSX. Both must go through the RN babel preset for the
  // Jest environment.
  //
  // The leading `.*` covers pnpm's layout: a real package lives under
  // node_modules/.pnpm/<name>@<version>/node_modules/<name>, so anchoring on
  // `node_modules/<name>` alone matches only the top-level symlink and leaves
  // the actual ESM source untransformed.
  transformIgnorePatterns: [
    'node_modules/(?!.*(?:(jest-)?react-native|@react-native(-community)?|@react-navigation|nativewind|react-native-css-interop|lucide-react-native|react-native-svg|@rn-primitives)/)',
  ],
  // Stub the native TurboModule specs so the app tree renders in tests.
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
};
