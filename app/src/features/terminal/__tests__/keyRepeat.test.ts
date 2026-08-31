import {
  clampRepeatDelay,
  KeyRepeater,
  MAX_REPEAT_DELAY_MS,
  MIN_REPEAT_DELAY_MS,
  REPEAT_DELAY_MS,
  REPEAT_INTERVAL_MS,
  repeatDelayMs,
} from '../keyRepeat';

describe('repeatDelayMs', () => {
  // A tap must never repeat, so the first gap sits out an ordinary press.
  it('waits before the first repeat', () => {
    expect(repeatDelayMs(1)).toBe(REPEAT_DELAY_MS);
    expect(repeatDelayMs(1)).toBeGreaterThan(repeatDelayMs(2));
  });

  it('settles into a steady interval', () => {
    expect(repeatDelayMs(2)).toBe(REPEAT_INTERVAL_MS);
    expect(repeatDelayMs(3)).toBe(REPEAT_INTERVAL_MS);
  });

  it('accelerates once the key is clearly held', () => {
    expect(repeatDelayMs(20)).toBeLessThan(REPEAT_INTERVAL_MS);
  });

  it('never drops to zero', () => {
    expect(repeatDelayMs(10_000)).toBeGreaterThan(0);
  });

  it('uses the configured first delay', () => {
    expect(repeatDelayMs(1, 900)).toBe(900);
    // Only the first gap is configurable; the stream keeps its own rate.
    expect(repeatDelayMs(2, 900)).toBe(REPEAT_INTERVAL_MS);
  });
});

describe('clampRepeatDelay', () => {
  it('keeps a sensible value', () => {
    expect(clampRepeatDelay(300)).toBe(300);
  });

  it('bounds the extremes', () => {
    expect(clampRepeatDelay(0)).toBe(MIN_REPEAT_DELAY_MS);
    expect(clampRepeatDelay(99_999)).toBe(MAX_REPEAT_DELAY_MS);
  });

  it('falls back for a value that is not a number', () => {
    expect(clampRepeatDelay(Number.NaN)).toBe(REPEAT_DELAY_MS);
  });
});

describe('KeyRepeater', () => {
  function harness(delay = REPEAT_DELAY_MS) {
    const fired: string[] = [];
    let now = 0;
    let seq = 0;
    const timers = new Map<number, { at: number; fn: () => void }>();

    const schedule = (fn: () => void, ms: number) => {
      seq += 1;
      timers.set(seq, { at: now + ms, fn });
      return seq as unknown as ReturnType<typeof setTimeout>;
    };
    const cancel = (id: ReturnType<typeof setTimeout>) => {
      timers.delete(id as unknown as number);
    };

    const repeater = new KeyRepeater(
      k => fired.push(k),
      schedule,
      cancel,
      delay,
    );

    const advance = (ms: number) => {
      const target = now + ms;
      for (;;) {
        let nextId: number | null = null;
        let nextAt = Infinity;
        for (const [id, t] of timers) {
          if (t.at <= target && t.at < nextAt) {
            nextAt = t.at;
            nextId = id;
          }
        }
        if (nextId === null) break;
        const t = timers.get(nextId)!;
        timers.delete(nextId);
        now = t.at;
        t.fn();
      }
      now = target;
    };

    return { repeater, advance, fired, pending: () => timers.size };
  }

  it('fires once immediately on press', () => {
    const h = harness();
    h.repeater.press('up');
    expect(h.fired).toEqual(['up']);
  });

  it('does not repeat for a tap', () => {
    const h = harness();
    h.repeater.press('up');
    h.advance(REPEAT_DELAY_MS - 50);
    h.repeater.release('up');
    h.advance(1000);
    expect(h.fired).toEqual(['up']);
  });

  it('repeats the held key while held', () => {
    const h = harness();
    h.repeater.press('down');
    h.advance(REPEAT_DELAY_MS + REPEAT_INTERVAL_MS * 3);
    expect(h.fired.length).toBeGreaterThan(3);
    expect(new Set(h.fired)).toEqual(new Set(['down']));
  });

  it('stops firing after release', () => {
    const h = harness();
    h.repeater.press('up');
    h.advance(REPEAT_DELAY_MS + REPEAT_INTERVAL_MS * 2);
    const atRelease = h.fired.length;
    h.repeater.release('up');
    h.advance(2000);
    expect(h.fired.length).toBe(atRelease);
  });

  /**
   * The bug this class exists to prevent.
   *
   * A finger sliding from one arrow to the next, or the scroll view claiming
   * the touch, leaves the first key's release unfired. With a repeater per key
   * both streams then run and the terminal receives up and down at once.
   */
  it('a second press ends the first key, even with no release', () => {
    const h = harness();
    h.repeater.press('up');
    h.advance(REPEAT_DELAY_MS + REPEAT_INTERVAL_MS * 2);
    h.repeater.press('down');
    h.fired.length = 0;
    h.advance(REPEAT_INTERVAL_MS * 10);

    expect(h.fired.length).toBeGreaterThan(0);
    expect(new Set(h.fired)).toEqual(new Set(['down']));
  });

  /**
   * A release for the previous key can arrive after the next is already down.
   * Honouring it would silence the live stream.
   */
  it('ignores a release for a key that is not held', () => {
    const h = harness();
    h.repeater.press('down');
    h.repeater.release('up');
    h.advance(REPEAT_DELAY_MS + REPEAT_INTERVAL_MS * 3);
    expect(h.fired.length).toBeGreaterThan(2);
  });

  it('reports which key is held', () => {
    const h = harness();
    expect(h.repeater.heldKey).toBeNull();
    h.repeater.press('left');
    expect(h.repeater.heldKey).toBe('left');
    h.repeater.release('left');
    expect(h.repeater.heldKey).toBeNull();
  });

  it('honours a configured delay', () => {
    const h = harness(800);
    h.repeater.press('up');
    h.advance(700);
    expect(h.fired.length).toBe(1);
    h.advance(200);
    expect(h.fired.length).toBeGreaterThan(1);
  });

  it('applies a delay change to the next press', () => {
    const h = harness(REPEAT_DELAY_MS);
    h.repeater.setDelay(200);
    h.repeater.press('up');
    h.advance(250);
    expect(h.fired.length).toBeGreaterThan(1);
  });

  it('speeds up the longer it is held', () => {
    const h = harness();
    h.repeater.press('up');
    h.advance(REPEAT_DELAY_MS + 500);
    const early = h.fired.length;
    h.advance(500);
    expect(h.fired.length - early).toBeGreaterThan(0);
  });

  /**
   * A press that did not cancel the previous timer would leave it scheduled,
   * where release cannot reach it and it fires into a key nobody is holding.
   */
  it('leaves no timer behind when keys are swapped', () => {
    const h = harness();
    h.repeater.press('up');
    h.repeater.press('down');
    h.repeater.press('left');
    expect(h.pending()).toBe(1);
    h.repeater.release('left');
    expect(h.pending()).toBe(0);
  });

  it('tolerates a release with nothing held', () => {
    const h = harness();
    expect(() => h.repeater.release()).not.toThrow();
    expect(() => h.repeater.stop()).not.toThrow();
  });
});
