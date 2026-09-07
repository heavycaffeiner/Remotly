// Generates deterministic byte fixtures for the M0-02 embedding spike. Each
// fixture is a raw byte stream the harness feeds to the terminal view. Run with
// `node generate.js` from this directory.
'use strict';

const fs = require('fs');
const path = require('path');

const out = __dirname;

function ansi(...seqs) {
  return '\x1b[' + seqs.join(';') + 'm';
}

const fixtures = {};

// 1. Typical interactive shell output with a prompt and a command.
fixtures['shell-prompt.bin'] = Buffer.from(
  '\x1b]0;user@host:~\x07' +
  '\x1b[32muser@host\x1b[0m:\x1b[32m~\x1b[0m$ ' +
  'ls -la\n' +
  'total 32\n' +
  'drwxr-xr-x 2 user user 4096 Aug 16 01:00 .\n' +
  'drwxr-xr-x 5 user user 4096 Aug 16 01:00 ..\n' +
  '-rw-r--r-- 1 user user  124 Aug 16 01:00 README.md\n' +
  '\x1b[32muser@host\x1b[0m:\x1b[32m~\x1b[0m$ '
);

// 2. Agent TUI full-screen redraw: box drawing plus cursor movement.
function tuiRedraw(rows, cols) {
  let s = '\x1b[2J\x1b[H';
  const rule = '─'.repeat(cols - 2);
  for (let r = 0; r < rows; r++) {
    s += '\x1b[' + (r + 1) + ';1H';
    if (r === 0) s += '┌' + rule + '┐';
    else if (r === rows - 1) s += '└' + rule + '┘';
    else s += '│' + ' '.repeat(cols - 2) + '│';
  }
  s += '\x1b[' + Math.floor(rows / 2) + ';4H' + 'agent: processing task 12/47';
  return s;
}
fixtures['tui-redraw.bin'] = Buffer.from(tuiRedraw(40, 120));

// 3. Large burst: 1 MiB of monotonically labeled lines, like `yes` or a dump.
function burst(bytes) {
  const line = 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\n';
  const b = Buffer.alloc(bytes);
  let off = 0;
  while (off < bytes) {
    const n = Math.min(line.length, bytes - off);
    b.write(line.slice(0, n), off);
    off += n;
  }
  return b;
}
fixtures['burst-1mb.bin'] = burst(1024 * 1024);

// 4. Split UTF-8: a multibyte sequence split at every byte boundary. The
// harness feeds each line as a separate chunk to prove chunking does not create
// replacement characters.
fixtures['split-utf8.bin'] = Buffer.from(
  'before \uD55C\uAE00 after\n'.repeat(16) +
  'emoji \u{1F600} end\n'.repeat(16)
);

// 5. Invalid UTF-8 followed by valid output, to prove malformed bytes fail
// safely and do not corrupt later output.
fixtures['invalid-utf8.bin'] = Buffer.concat([
  Buffer.from('valid before\n'),
  Buffer.from([0xff, 0xfe, 0x80, 0xc0, 0xaf]),
  Buffer.from('\nvalid after \uD55C\uAE00\n'),
]);

// 6. Long lines that exceed the terminal width.
fixtures['long-lines.bin'] = Buffer.from(
  ('x'.repeat(500) + '\n').repeat(8)
);

const manifest = {};
for (const [name, buf] of Object.entries(fixtures)) {
  fs.writeFileSync(path.join(out, name), buf);
  manifest[name] = { bytes: buf.length };
}
fs.writeFileSync(path.join(out, 'manifest.json'), JSON.stringify(manifest, null, 2));
console.log('wrote', Object.keys(fixtures).length, 'fixtures');
for (const [name, buf] of Object.entries(fixtures)) {
  console.log(' ', name, buf.length, 'bytes');
}
