const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const { withNativeWind } = require('nativewind/metro');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  resolver: {
    resolveRequest: (context, moduleName, platform) => {
      // React Native 0.87 removed the legacy renderer shim. Reanimated, pulled
      // in transitively by nativewind, still requires it on the branch it takes
      // when Fabric is off. This app is Fabric-only, so that branch never runs,
      // but Metro walks the require anyway and fails the whole bundle.
      //
      // Point it at the Fabric shim, which exports the same
      // findHostInstance_DEPRECATED the caller reads.
      if (moduleName === 'react-native/Libraries/Renderer/shims/ReactNative') {
        return {
          type: 'sourceFile',
          filePath: path.resolve(
            __dirname,
            'node_modules/react-native/Libraries/Renderer/shims/ReactFabric.js',
          ),
        };
      }
      return context.resolveRequest(context, moduleName, platform);
    },
  },
};

module.exports = withNativeWind(
  mergeConfig(getDefaultConfig(__dirname), config),
  { input: './global.css' },
);
