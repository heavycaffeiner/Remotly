import {
  DEFAULT_FILE_VIEW,
  isHidden,
  viewEntries,
  type FileEntry,
  type FileView,
} from '../files';

function entry(name: string, over: Partial<FileEntry> = {}): FileEntry {
  return {
    name,
    isDir: false,
    isSymlink: false,
    size: 0,
    mtime: 0,
    perm: 0,
    ...over,
  };
}

function names(entries: readonly FileEntry[]): string[] {
  return entries.map(e => e.name);
}

const view = (over: Partial<FileView> = {}): FileView => ({
  ...DEFAULT_FILE_VIEW,
  ...over,
});

describe('isHidden', () => {
  it('treats a leading dot as hidden', () => {
    expect(isHidden(entry('.bashrc'))).toBe(true);
    expect(isHidden(entry('.config', { isDir: true }))).toBe(true);
  });

  it('does not treat an inner dot as hidden', () => {
    expect(isHidden(entry('archive.tar.gz'))).toBe(false);
    expect(isHidden(entry('README'))).toBe(false);
  });
});

describe('viewEntries', () => {
  const listing = [
    entry('README.md', { size: 300, mtime: 30 }),
    entry('.bashrc', { size: 100, mtime: 10 }),
    entry('src', { isDir: true, mtime: 20 }),
    entry('.git', { isDir: true, mtime: 40 }),
    entry('a.txt', { size: 900, mtime: 50 }),
  ];

  it('hides dotfiles by default and shows them on request', () => {
    expect(names(viewEntries(listing))).toEqual(['src', 'README.md', 'a.txt']);
    expect(names(viewEntries(listing, view({ showHidden: true })))).toEqual([
      '.git',
      'src',
      '.bashrc',
      'README.md',
      'a.txt',
    ]);
  });

  it('keeps directories ahead of files in every order', () => {
    for (const sortKey of ['name', 'size', 'mtime', 'kind'] as const) {
      for (const direction of ['asc', 'desc'] as const) {
        const out = viewEntries(listing, view({ sortKey, direction }));
        const firstFile = out.findIndex(e => !e.isDir);
        const lastDir = out.map(e => e.isDir).lastIndexOf(true);
        expect(lastDir).toBeLessThan(firstFile === -1 ? out.length : firstFile);
      }
    }
  });

  it('sorts by size, largest last ascending', () => {
    const out = viewEntries(listing, view({ sortKey: 'size' }));
    expect(names(out)).toEqual(['src', 'README.md', 'a.txt']);
  });

  it('reverses only within a group when descending', () => {
    const out = viewEntries(
      listing,
      view({ sortKey: 'size', direction: 'desc', showHidden: true }),
    );
    // Directories still lead; files run largest first.
    expect(names(out)).toEqual([
      '.git',
      'src',
      'a.txt',
      'README.md',
      '.bashrc',
    ]);
  });

  it('sorts by modification time', () => {
    const out = viewEntries(listing, view({ sortKey: 'mtime' }));
    expect(names(out)).toEqual(['src', 'README.md', 'a.txt']);
  });

  it('groups by extension when sorting by kind', () => {
    const files = [
      entry('b.zip'),
      entry('a.txt'),
      entry('c.md'),
      entry('d.txt'),
      entry('plain'),
    ];
    // No extension sorts first, then md, txt, zip; ties fall back to name.
    expect(names(viewEntries(files, view({ sortKey: 'kind' })))).toEqual([
      'plain',
      'c.md',
      'a.txt',
      'd.txt',
      'b.zip',
    ]);
  });

  it('does not treat a dotfile as having an extension', () => {
    const files = [entry('.bashrc'), entry('a.rc')];
    const out = viewEntries(files, view({ sortKey: 'kind', showHidden: true }));
    // ".bashrc" has no extension, so it groups ahead of the ".rc" file.
    expect(names(out)).toEqual(['.bashrc', 'a.rc']);
  });

  it('searches case-insensitively on a substring', () => {
    expect(names(viewEntries(listing, view({ query: 'readme' })))).toEqual([
      'README.md',
    ]);
    expect(names(viewEntries(listing, view({ query: '.MD' })))).toEqual([
      'README.md',
    ]);
  });

  it('applies the hidden filter before the search', () => {
    // "git" matches .git, but it stays hidden unless hidden files are shown.
    expect(viewEntries(listing, view({ query: 'git' }))).toHaveLength(0);
    expect(
      names(viewEntries(listing, view({ query: 'git', showHidden: true }))),
    ).toEqual(['.git']);
  });

  it('orders equal keys by raw name so the result is stable', () => {
    const same = [
      entry('b', { size: 5 }),
      entry('a', { size: 5 }),
      entry('c', { size: 5 }),
    ];
    expect(names(viewEntries(same, view({ sortKey: 'size' })))).toEqual([
      'a',
      'b',
      'c',
    ]);
  });

  it('does not mutate the input', () => {
    const input = [entry('b'), entry('a')];
    const copy = names(input);
    viewEntries(input, view({ sortKey: 'name', direction: 'desc' }));
    expect(names(input)).toEqual(copy);
  });
});
