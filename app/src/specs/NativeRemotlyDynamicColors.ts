import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';
import type { UnsafeObject } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Spec for Material You (dynamic color). On Android 12 and later the system
// derives a color scheme from the wallpaper; this module reads those colors
// out of the Material3 dynamic themes and hands them to JS as hex strings.

export interface DynamicColorsResult {
  /** False when the platform cannot produce dynamic colors. */
  available: boolean;
  /**
   * Semantic color roles as hex strings, present only when available.
   *
   * Codegen has no mapped-object type, so these cross as opaque objects and
   * `dynamicColorsFor` validates each role it reads.
   */
  light?: UnsafeObject;
  dark?: UnsafeObject;
}

export interface Spec extends TurboModule {
  get(): Promise<DynamicColorsResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyDynamicColors');
