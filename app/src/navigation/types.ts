// Route maps. Params must stay serializable: no credentials, private keys,
// transport objects, or large records ever travel through navigation.

/** The three top-level destinations shown in the navigation bar or rail. */
export type MainTab = 'Hosts' | 'Sessions' | 'Settings';

export const MAIN_TABS: readonly MainTab[] = ['Hosts', 'Sessions', 'Settings'];

export type RootStackParamList = {
  /** The tabbed shell. `tab` selects the initial destination. */
  Main: { tab?: MainTab } | undefined;
  // `d` is the base64url pairing payload from the remotly://pair?d=... deep
  // link (see navigation/linking.ts); the screen rebuilds the full URI.
  Pairing: { d?: string };
  Workspace: { hostId: string };
  SshTerminal: { hostId: string };
  /** Omit `hostId` to create a host; pass it to edit an existing one. */
  SshHostEditor: { hostId?: string } | undefined;
  Files: { hostId: string; kind: 'daemon' | 'ssh' };
};
