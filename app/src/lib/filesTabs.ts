// Where each file browser tab is looking.
//
// A files tab is unmounted when another tab is selected, so its directory
// cannot live in component state: coming back would drop the user at the root
// of a tree they had walked into. This keeps one directory per tab id, outside
// the React tree, for exactly the same reason the SSH sessions do.

const cwds = new Map<string, string>();

/** The directory a tab is showing, defaulting to the root. */
export function filesTabCwd(tabId: string): string {
  return cwds.get(tabId) ?? '/';
}

export function setFilesTabCwd(tabId: string, path: string): void {
  if (tabId === '') return;
  cwds.set(tabId, path);
}

/** Drops a closed tab's directory so the map cannot grow forever. */
export function forgetFilesTab(tabId: string): void {
  cwds.delete(tabId);
}

/** Test seam. */
export function resetFilesTabs(): void {
  cwds.clear();
}
