// A static stand-in for reanimated under Jest.
//
// Paper 6 animates TextInput, Switch, FAB, and Checkbox with reanimated, whose
// real entry needs the JSI worklets module. Without it reanimated falls back to
// a web implementation that wants a DOM, which this Android-only app has no
// reason to carry. Animations resolve to their target value immediately here,
// so a rendered tree is the settled one and the tests see final state.
'use strict';

const React = require('react');
const {
  View,
  Text,
  ScrollView,
  FlatList,
  Image,
} = require('react-native');

const identity = value => value;

const Easing = {
  linear: identity,
  ease: identity,
  quad: identity,
  cubic: identity,
  bezier: () => identity,
  bezierFn: () => identity,
  in: identity,
  out: identity,
  inOut: identity,
};

const ReduceMotion = {
  System: 'system',
  Always: 'always',
  Never: 'never',
};

const Animated = {
  View,
  Text,
  ScrollView,
  FlatList,
  Image,
  createAnimatedComponent: component => component,
};

module.exports = {
  __esModule: true,
  default: Animated,
  Animated,
  Easing,
  ReduceMotion,

  useSharedValue: initial => ({ value: initial }),
  useDerivedValue: factory => ({ value: factory() }),
  useAnimatedStyle: factory => factory(),
  useAnimatedReaction: () => undefined,
  useAnimatedRef: () => React.createRef(),
  useAnimatedProps: factory => factory(),
  useAnimatedScrollHandler: () => () => undefined,

  // Animations settle at once: the value passed in is the value read back.
  withTiming: toValue => toValue,
  withSpring: toValue => toValue,
  withDecay: toValue => toValue,
  withDelay: (_delay, animation) => animation,
  withSequence: (...animations) => animations[animations.length - 1],
  withRepeat: animation => animation,
  cancelAnimation: () => undefined,

  interpolate: (_value, _inputRange, outputRange) =>
    Array.isArray(outputRange) ? outputRange[0] : 0,
  interpolateColor: (_value, _inputRange, outputRange) =>
    Array.isArray(outputRange) ? outputRange[0] : 'transparent',
  measure: () => null,
  runOnJS: fn => fn,
  runOnUI: fn => fn,
  Extrapolation: { CLAMP: 'clamp', EXTEND: 'extend', IDENTITY: 'identity' },
};
