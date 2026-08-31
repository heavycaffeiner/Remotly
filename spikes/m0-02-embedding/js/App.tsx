// App.tsx - M0-02 Lynx harness. Mounts the native terminal component, feeds
// deterministic fixtures, and measures throughput and input latency. This is a
// disposable spike; the exact Lynx custom-component JS API must be aligned to
// the target Lynx release (see README).
import { useCallback, useEffect, useRef, useState } from '@lynx-js/react';

type TerminalRef = {
  feedOutput(bytes: Uint8Array): void;
  resize(cols: number, rows: number): void;
  setFontSize(px: number): void;
};

export function App() {
  const terminal = useRef<TerminalRef | null>(null);
  const [report, setReport] = useState('idle');

  const onCommittedInput = useCallback((bytes: Uint8Array) => {
    // Record input latency: timestamped when the byte was injected vs emitted.
  }, []);

  const runFixture = useCallback(async (name: string) => {
    const bytes = await fetchFixture(name);
    const start = performance.now();
    // Feed in realistic chunks (4 KiB), the same way the transport layer would.
    for (let off = 0; off < bytes.length; off += 4096) {
      terminal.current?.feedOutput(bytes.subarray(off, off + 4096));
      if (off % (64 * 4096) === 0) {
        await new Promise((r) => requestAnimationFrame(r));
      }
    }
    const elapsed = performance.now() - start;
    setReport(`${name}: ${bytes.length} bytes in ${elapsed.toFixed(1)} ms`);
  }, []);

  useEffect(() => {
    runFixture('shell-prompt.bin');
  }, [runFixture]);

  return (
    <view style={{ flex: 1, flexDirection: 'column' }}>
      <terminal
        ref={terminal}
        onCommittedInput={onCommittedInput}
        style={{ flex: 1, backgroundColor: '#000000' }}
      />
      <view style={{ height: 48 }}>
        <text>{report}</text>
      </view>
    </view>
  );
}

async function fetchFixture(name: string): Promise<Uint8Array> {
  // Resolve the bundled fixture asset; the loader depends on the Lynx asset API.
  const url = `asset://fixtures/${name}`;
  const res = await (globalThis as any).fetch(url);
  return new Uint8Array(await res.arrayBuffer());
}
