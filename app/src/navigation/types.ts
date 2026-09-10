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
  /**
   * A herdr terminal on a host. `session` is the herdr session it belongs to,
   * null for the default one. Omit `workspaceId` to land on whichever
   * workspace herdr has focused, which is what opening herdr on a host means;
   * `label` only saves the header a frame while the first snapshot arrives.
   */
  HerdrWorkspace: {
    hostId: string;
    hostName: string;
    workspaceId?: string;
    label?: string;
    session: string | null;
  };
};
