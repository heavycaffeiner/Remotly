import { encodeBase64 } from '../base64';
import type { TransferBackend, TransferHandle } from '../files';
import {
  MAX_IMAGE_BYTES,
  pasteImage,
  pastedImageName,
  shellQuote,
} from '../imagePaste';

function fakeTransfers(
  overrides: Partial<TransferBackend> = {},
): TransferBackend & {
  written: number[];
  completed: string[];
  cancelled: string[];
} {
  const written: number[] = [];
  const completed: string[] = [];
  const cancelled: string[] = [];
  const base = {
    kind: 'daemon' as const,
    capabilities: {} as never,
    written,
    completed,
    cancelled,
    async startUpload(path: string, size: number): Promise<TransferHandle> {
      return { id: 'x1', direction: 'upload', path, size };
    },
    async writeChunk(_id: string, _offset: number, data: Uint8Array) {
      written.push(data.length);
      return data.length;
    },
    async completeUpload(id: string) {
      completed.push(id);
    },
    async startDownload(): Promise<TransferHandle> {
      throw new Error('not used');
    },
    async status() {
      return { state: 'done' as const, received: 0, total: 0 };
    },
    async cancel(id: string) {
      cancelled.push(id);
    },
  };
  return Object.assign(base, overrides);
}

function image(bytes: number): { data: string; width: number; height: number } {
  return {
    data: encodeBase64(new Uint8Array(bytes).fill(7)),
    width: 10,
    height: 10,
  };
}

describe('pastedImageName', () => {
  it('is a png with no separators in it', () => {
    const name = pastedImageName(Date.UTC(2026, 0, 2, 3, 4, 5));
    expect(name.endsWith('.png')).toBe(true);
    expect(name).not.toContain('/');
    expect(name).not.toContain(':');
  });

  it('differs between two moments', () => {
    expect(pastedImageName(1000)).not.toBe(pastedImageName(90_000));
  });
});

describe('shellQuote', () => {
  it('wraps a plain path', () => {
    expect(shellQuote('/home/a/b.png')).toBe("'/home/a/b.png'");
  });

  // A name that closed the quote could run a command.
  it('neutralises an embedded quote', () => {
    const quoted = shellQuote("/tmp/it's; rm -rf ~");
    expect(quoted.startsWith("'")).toBe(true);
    expect(quoted.endsWith("'")).toBe(true);
    expect(quoted).toBe(`'/tmp/it'\\''s; rm -rf ~'`);
  });
});

describe('pasteImage', () => {
  it('uploads the bytes and returns a quoted path', async () => {
    const t = fakeTransfers();
    const result = await pasteImage(image(100), t, '/home/dev', 1000);

    expect(result.path).toContain('/home/dev/.remotly/pasted/');
    expect(result.text).toContain("'");
    expect(result.text.endsWith(' ')).toBe(true);
    expect(t.written.reduce((a, b) => a + b, 0)).toBe(100);
    expect(t.completed).toEqual(['x1']);
  });

  it('chunks a large image', async () => {
    const t = fakeTransfers();
    await pasteImage(image(200_000), t, '/home/dev');
    expect(t.written.length).toBeGreaterThan(1);
    expect(t.written.reduce((a, b) => a + b, 0)).toBe(200_000);
  });

  it('tolerates a trailing slash on home', async () => {
    const t = fakeTransfers();
    const r = await pasteImage(image(10), t, '/home/dev/');
    expect(r.path).not.toContain('//.remotly');
  });

  it('refuses an empty clipboard image', async () => {
    const t = fakeTransfers();
    await expect(
      pasteImage({ data: '', width: 0, height: 0 }, t, '/home/dev'),
    ).rejects.toThrow(/empty/i);
  });

  it('refuses an oversized image', async () => {
    const t = fakeTransfers();
    await expect(
      pasteImage(image(MAX_IMAGE_BYTES + 1), t, '/home/dev'),
    ).rejects.toThrow(/too large/i);
  });

  // A half-written file must not be left behind with its path typed out.
  it('cancels the upload when a chunk fails', async () => {
    const t = fakeTransfers({
      async writeChunk() {
        throw new Error('link dropped');
      },
    });
    await expect(pasteImage(image(100), t, '/home/dev')).rejects.toThrow(
      /link dropped/,
    );
    expect(t.cancelled).toEqual(['x1']);
    expect(t.completed).toEqual([]);
  });
});
