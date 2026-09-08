module.exports = {
  preset: '@react-native/jest-preset',
  // The RN preset's transform covers js/ts/tsx only. Paper 6 ships ESM under
  // lib/module, so babel has to see .mjs too, and the icon package requires
  // its .ttf at module load: babel must not try to parse the font as source.
  transform: {
    '^.+\\.(js|jsx|mjs|ts|tsx)$': 'babel-jest',
    '^.+\\.(ttf|otf)$': require.resolve(
      '@react-native/jest-preset/jest/assetFileTransformer.js',
    ),
  },
  moduleFileExtensions: ['js', 'jsx', 'mjs', 'ts', 'tsx', 'json', 'node'],
  // Paper 6 animates several of its components with reanimated, which needs the
  // JSI worklets module. A static stand-in settles every animation at its
  // target value, which is the state these tests assert against.
  moduleNameMapper: {
    '^react-native-reanimated$': '<rootDir>/jest.reanimated-stub.js',
    '^react-native-worklets$': '<rootDir>/jest.worklets-stub.js',
  },
  // @react-navigation and react-native-paper both ship ESM-only modules, which
  // must go through the RN babel preset for the Jest environment.
  //
  // The leading `.*` covers pnpm's layout: a real package lives under
  // node_modules/.pnpm/<name>@<version>/node_modules/<name>, so anchoring on
  // `node_modules/<name>` alone matches only the top-level symlink and leaves
  // the actual ESM source untransformed.
  transformIgnorePatterns: [
    'node_modules/(?!.*(?:(jest-)?react-native|@react-native(-community)?|@react-navigation|react-native-paper|@react-native-vector-icons)/)',
  ],
  // Stub the native TurboModule specs so the app tree renders in tests.
  setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
};
