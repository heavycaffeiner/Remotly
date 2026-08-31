/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';

import App from '../src/App';

// Verifies the provider wiring (SafeArea + Paper MD3 dark theme) renders the
// demo screen. No native modules are involved.
test('renders correctly', async () => {
  await ReactTestRenderer.act(() => {
    ReactTestRenderer.create(<App />);
  });
});
