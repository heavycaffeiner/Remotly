// Route maps. Params must stay serializable: no credentials, private keys,
// or large records ever travel through navigation.

/** The two top-level destinations shown in the navigation bar or rail. */
export type MainTab = 'Hosts' | 'Settings';

export const MAIN_TABS: readonly MainTab[] = ['Hosts', 'Settings'];

export type RootStackParamList = {
  /** The tabbed shell. `tab` selects the initial destination. */
  Main: { tab?: MainTab } | undefined;
  SshTerminal: { hostId: string };
  /** Omit `hostId` to create a host; pass it to edit an existing one. */
  SshHostEditor: { hostId?: string } | undefined;
  Files: { hostId: string };
};
