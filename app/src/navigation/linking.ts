// Deep-link config for react-navigation's Linking API. The manifest intent
// filter (RN-01) accepts `remotly://pair`. This maps the `pair` path onto the
// Pairing screen; the `d` query param (the base64url pairing payload) is
// handed to the screen, which rebuilds the full URI and re-parses it with
// lib/pairing.ts. That module owns validation (base64url payload, key lengths,
// expiry) and rejects malformed input.
//
// Covers both warm and cold start: on cold start the OS delivers the launch
// intent and Linking.getInitialURL() derives the same route once JS is ready.
// The one-shot `remotly.pairing.takePending` (RN-04 module) is a belt-and-
// braces fallback for the edge where the intent lands before the navigator is
// up; the screen drains it on mount and dedupes against the param.
export const linking = {
  prefixes: ['remotly://'],
  config: {
    screens: {
      Pairing: 'pair',
    },
  },
};
