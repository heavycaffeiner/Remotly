import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The granted download folder has to be readable where the download starts.
 *
 * beginDownload is reached from the onSink subscription, which is registered
 * once on mount with an empty dependency list. A closure like that keeps the
 * props and state it was created with, so reading settings.downloadFolderUri
 * inside it saw the value from mount forever: the folder the user had just
 * granted was written to storage and never seen again, and every download
 * asked for it once more.
 *
 * Checked at the source, since the defect is which binding is read rather than
 * anything observable without the whole navigation and native stack.
 */
describe('download folder wiring', () => {
  const screen = readFileSync(join(__dirname, '..', 'FilesScreen.tsx'), 'utf8');

  /** Everything reached from a mount-time closure reads the ref. */
  it('resolves the folder through a ref, not captured state', () => {
    const fn = /async function beginDownload\(([\s\S]*?)\n {2}\}/.exec(screen);
    expect(fn).not.toBeNull();
    expect(fn?.[1]).toContain('folderUriRef.current');
    expect(fn?.[1]).not.toContain('settings.downloadFolderUri');
  });

  /**
   * The retry runs immediately after the pick, so the ref has to carry the new
   * folder before the store has finished writing it.
   */
  it('records the picked folder before retrying the download', () => {
    const handler =
      /const wanted = pendingFolderPickRef\.current;([\s\S]*?)return;/.exec(
        screen,
      );
    expect(handler).not.toBeNull();
    const body = handler?.[1] ?? '';
    expect(body).toContain('folderUriRef.current = f.uri');
    expect(body.indexOf('folderUriRef.current = f.uri')).toBeLessThan(
      body.indexOf('beginDownload'),
    );
  });

  /** A folder changed from Settings still has to reach the ref. */
  it('keeps the ref in step with the stored setting', () => {
    expect(screen).toMatch(
      /folderUriRef\.current = settings\.downloadFolderUri;\s*\n\s*\}, \[settings\.downloadFolderUri\]\);/,
    );
  });

  /** The conflict resolution creates files in the same folder. */
  it('resolves keep-both against the same folder', () => {
    const fn = /function resolveKeepBoth\(\)([\s\S]*?)\n {2}\}/.exec(screen);
    expect(fn?.[1]).toContain('folderUriRef.current');
  });
});
