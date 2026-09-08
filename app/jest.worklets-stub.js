// A stand-in for react-native-worklets under Jest.
//
// Paper 6's extended FAB schedules a layout measurement on the UI runtime. The
// real module needs JSI; here the work runs inline on the JS thread, which is
// where the tests already observe it.
'use strict';

module.exports = {
  __esModule: true,
  scheduleOnUI: fn => fn(),
  scheduleOnRN: fn => fn(),
  runOnUI: fn => fn,
  runOnJS: fn => fn,
  createWorkletRuntime: () => ({}),
  isWorkletFunction: () => false,
};
