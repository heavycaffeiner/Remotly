import {
  entryAccessibilityLabel,
  entryDescription,
  entryIcon,
  entryKey,
  formatSize,
  nameExists,
  transferLabel,
  transferProgress,
  numberedName,
  splitName,
  uniqueName,
  validateName,
  type Transfer,
} from '../filePresentation';
import type { FileEntry } from '../../../lib/files';

const file = (over: Partial<FileEntry> = {}): FileEntry => ({
  name: 'report.txt',
  isDir: false,
  isSymlink: false,
  size: 1024,
  mtime: 0,
  perm: 0,
  ...over,
});

describe('entryKey', () => {
  it('is stable for the same entry in the same directory', () => {
    expect(entryKey('/home', file())).toBe(entryKey('/home', file()));
  });

  it('separates the same name in different directories', () => {
    // A bare name would collide across a navigation and recycle the wrong row.
    expect(entryKey('/a', file())).not.toBe(entryKey('/b', file()));
  });

  it('keeps NFC and NFD names distinct', () => {
    // Two different files on the server. Collapsing them would send an
    // operation to the wrong one.
    const nfc = file({ name: '\ud55c' });
    const nfd = file({ name: '\u1112\u1161\u11ab' });
    expect(entryKey('/', nfc)).not.toBe(entryKey('/', nfd));
  });

  it('does not case fold', () => {
    expect(entryKey('/', file({ name: 'README' }))).not.toBe(
      entryKey('/', file({ name: 'readme' })),
    );
  });
});

describe('entryIcon', () => {
  it('distinguishes files, directories, and links', () => {
    expect(entryIcon(file())).toBe('file');
    expect(entryIcon(file({ isDir: true }))).toBe('folder');
    expect(entryIcon(file({ isSymlink: true }))).toBe('link-off');
  });

  it('reports a symlinked directory as a link', () => {
    expect(entryIcon(file({ isDir: true, isSymlink: true }))).toBe('link-off');
  });
});

describe('formatSize', () => {
  it('formats bytes and binary multiples', () => {
    expect(formatSize(0)).toBe('0 B');
    expect(formatSize(512)).toBe('512 B');
    expect(formatSize(1024)).toBe('1.0 KiB');
    expect(formatSize(1024 * 1024)).toBe('1.0 MiB');
    expect(formatSize(1024 ** 3)).toBe('1.0 GiB');
  });

  it('drops the decimal for large values', () => {
    expect(formatSize(150 * 1024)).toBe('150 KiB');
  });

  it('rejects a nonsense size', () => {
    expect(formatSize(-1)).toBe('');
    expect(formatSize(NaN)).toBe('');
    expect(formatSize(Infinity)).toBe('');
  });
});

describe('entryDescription', () => {
  it('omits the size for a directory', () => {
    expect(entryDescription(file({ isDir: true, size: 4096 }))).not.toContain(
      'KiB',
    );
  });

  it('includes the size for a file', () => {
    expect(entryDescription(file({ size: 2048 }))).toContain('2.0 KiB');
  });
});

describe('entryAccessibilityLabel', () => {
  it('states what the row is, not only its name', () => {
    expect(entryAccessibilityLabel(file())).toContain('file');
    expect(entryAccessibilityLabel(file({ isDir: true }))).toContain('folder');
    expect(entryAccessibilityLabel(file({ isSymlink: true }))).toContain(
      'link',
    );
  });

  it('includes the name verbatim', () => {
    expect(entryAccessibilityLabel(file({ name: '보고서.txt' }))).toContain(
      '보고서.txt',
    );
  });
});

describe('validateName', () => {
  it('accepts an ordinary name', () => {
    expect(validateName('report.txt')).toBeNull();
    expect(validateName('보고서.txt')).toBeNull();
    expect(validateName('.hidden')).toBeNull();
  });

  it('rejects an empty name', () => {
    expect(validateName('')).not.toBeNull();
  });

  it('rejects dot segments', () => {
    expect(validateName('.')).not.toBeNull();
    expect(validateName('..')).not.toBeNull();
  });

  it('rejects a path separator', () => {
    // A separator would move the file rather than rename it.
    expect(validateName('a/b')).not.toBeNull();
    expect(validateName('a\\b')).not.toBeNull();
  });

  it('rejects a NUL', () => {
    // It terminates the string in most server APIs, so the name shown and the
    // name acted on would differ.
    expect(validateName('a\u0000b')).not.toBeNull();
  });

  it('rejects control characters', () => {
    expect(validateName('a\nb')).not.toBeNull();
    expect(validateName('a\tb')).not.toBeNull();
  });

  it('rejects an over-long name', () => {
    expect(validateName('x'.repeat(256))).not.toBeNull();
    expect(validateName('x'.repeat(255))).toBeNull();
  });
});

describe('nameExists', () => {
  const entries = [file({ name: 'a.txt' }), file({ name: '한글.txt' })];

  it('matches exactly', () => {
    expect(nameExists(entries, 'a.txt')).toBe(true);
    expect(nameExists(entries, 'b.txt')).toBe(false);
  });

  it('does not case fold', () => {
    expect(nameExists(entries, 'A.TXT')).toBe(false);
  });

  it('does not normalize', () => {
    expect(nameExists(entries, '\u1112\u1161\u11ab.txt')).toBe(false);
  });
});

describe('uniqueName', () => {
  it('returns the name unchanged when it is free', () => {
    expect(uniqueName([], 'report.txt')).toBe('report.txt');
  });

  it('inserts a counter before the extension', () => {
    const entries = [file({ name: 'report.txt' })];
    expect(uniqueName(entries, 'report.txt')).toBe('report (1).txt');
  });

  it('skips names that are also taken', () => {
    const entries = [
      file({ name: 'report.txt' }),
      file({ name: 'report (1).txt' }),
    ];
    expect(uniqueName(entries, 'report.txt')).toBe('report (2).txt');
  });

  it('treats a leading dot as part of the name, not an extension', () => {
    const entries = [file({ name: '.bashrc' })];
    expect(uniqueName(entries, '.bashrc')).toBe('.bashrc (1)');
  });

  it('handles a name with no extension', () => {
    const entries = [file({ name: 'Makefile' })];
    expect(uniqueName(entries, 'Makefile')).toBe('Makefile (1)');
  });
});

describe('transfers', () => {
  const transfer = (over: Partial<Transfer> = {}): Transfer => ({
    id: 't1',
    direction: 'upload',
    displayName: 'report.txt',
    remotePath: '/home/report.txt',
    transferred: 0,
    total: 100,
    state: 'running',
    resumable: true,
    ...over,
  });

  it('reports progress as a fraction', () => {
    expect(transferProgress(transfer({ transferred: 50 }))).toBe(0.5);
  });

  it('returns null when the total is unknown', () => {
    // An indeterminate bar is honest; a full one would not be.
    expect(transferProgress(transfer({ total: 0 }))).toBeNull();
  });

  it('clamps a transferred count past the total', () => {
    expect(transferProgress(transfer({ transferred: 500 }))).toBe(1);
  });

  it('labels each state distinctly', () => {
    const labels = new Set(
      (
        [
          'queued',
          'running',
          'done',
          'failed',
          'cancelled',
          'conflict',
        ] as const
      ).map(state => transferLabel(transfer({ state }))),
    );
    expect(labels.size).toBe(6);
  });

  it('names the direction in the running label', () => {
    expect(transferLabel(transfer({ direction: 'download' }))).toContain(
      'Downloading',
    );
    expect(transferLabel(transfer({ direction: 'upload' }))).toContain(
      'Uploading',
    );
  });
});

describe('numbered names for a collision', () => {
  /** What a desktop file manager produces, so the result is recognisable. */
  it('inserts the counter before the extension', () => {
    expect(numberedName('report.txt', 1)).toBe('report (1).txt');
    expect(numberedName('report.txt', 12)).toBe('report (12).txt');
  });

  it('appends to a name with no extension', () => {
    expect(numberedName('README', 1)).toBe('README (1)');
  });

  /** A leading dot is a hidden file, not an extension. */
  it('treats a leading dot as part of the name', () => {
    expect(numberedName('.bashrc', 1)).toBe('.bashrc (1)');
  });

  it('uses only the last dot', () => {
    expect(numberedName('archive.tar.gz', 2)).toBe('archive.tar (2).gz');
  });

  it('splits a name into its stem and extension', () => {
    expect(splitName('a.txt')).toEqual({ stem: 'a', ext: '.txt' });
    expect(splitName('a')).toEqual({ stem: 'a', ext: '' });
    expect(splitName('.hidden')).toEqual({ stem: '.hidden', ext: '' });
  });
});
