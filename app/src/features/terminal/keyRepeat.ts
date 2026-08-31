// Auto-repeat timing for a held key.
//
// A held arrow should behave like a held key on a physical keyboard: one press
// straight away, a pause long enough that an ordinary tap never repeats, then
// a steady stream. The stream accelerates to a floor, because moving a cursor
// across a long line one slow tick at a time is worse than useless.
//
// One repeater serves a whole key row rather than one per key. Only one key can
// be held at a time, and a per-key repeater cannot enforce that: a finger that
// slides from one key to the next, or a scroll that steals the touch, leaves
// the first key's release unfired and two streams running at once.

/** Delay from press to the first repeat, when none is configured. */
export const REPEAT_DELAY_MS = 400;

/** Interval between repeats once the stream has started. */
export const REPEAT_INTERVAL_MS = 50;

/** Bounds for a user-chosen delay. */
export const MIN_REPEAT_DELAY_MS = 150;
export const MAX_REPEAT_DELAY_MS = 1000;

/** Repeats at the slow rate before the interval starts shortening. */
const ACCELERATE_AFTER = 6;

/** The interval never drops below this, however long the key is held. */
const MIN_INTERVAL_MS = 20;

/** Clamps a stored preference into the supported range. */
export function clampRepeatDelay(ms: number): number {
  if (!Number.isFinite(ms)) return REPEAT_DELAY_MS;
  return Math.min(
    MAX_REPEAT_DELAY_MS,
    Math.max(MIN_REPEAT_DELAY_MS, Math.round(ms)),
  );
}

/**
 * Delay before repeat number [count], where 1 is the first repeat.
 *
 * Returns [delayMs] for the first, then the steady interval, which shortens
 * once the key has clearly been held rather than tapped.
 */
export function repeatDelayMs(
  count: number,
  delayMs: number = REPEAT_DELAY_MS,
): number {
  if (count <= 1) return clampRepeatDelay(delayMs);
  if (count <= ACCELERATE_AFTER) return REPEAT_INTERVAL_MS;
  return Math.max(MIN_INTERVAL_MS, Math.round(REPEAT_INTERVAL_MS / 2));
}

/**
 * Drives auto-repeat for whichever key is currently held.
 *
 * The caller supplies the scheduler so this is testable with fake timers and
 * usable from React without importing a platform timer type.
 */
export class KeyRepeater {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private count = 0;
  private key: string | null = null;

  constructor(
    private readonly fire: (key: string) => void,
    private readonly schedule: (
      fn: () => void,
      ms: number,
    ) => ReturnType<typeof setTimeout> = setTimeout,
    private readonly cancel: (
      id: ReturnType<typeof setTimeout>,
    ) => void = clearTimeout,
    private delayMs: number = REPEAT_DELAY_MS,
  ) {}

  /** The key being held, or null. */
  get heldKey(): string | null {
    return this.key;
  }

  /** Changes the first-repeat delay. Takes effect on the next press. */
  setDelay(ms: number): void {
    this.delayMs = clampRepeatDelay(ms);
  }

  /**
   * Presses [key] once and arms the repeat.
   *
   * Any key already held is released first, so a finger sliding from one key
   * to another cannot leave two streams running.
   */
  press(key: string): void {
    this.stop();
    this.key = key;
    this.fire(key);
    this.count = 0;
    this.arm();
  }

  /**
   * Releases [key].
   *
   * A key that is not the one being held is ignored: a stale release arriving
   * after the next key is already down would otherwise kill the live stream.
   * Omit the argument to release whatever is held.
   */
  release(key?: string): void {
    if (key !== undefined && this.key !== key) return;
    this.stop();
  }

  /** Releases whatever is held. Safe to call when nothing is. */
  stop(): void {
    if (this.timer !== null) {
      this.cancel(this.timer);
      this.timer = null;
    }
    this.count = 0;
    this.key = null;
  }

  private arm(): void {
    this.count += 1;
    const held = this.key;
    this.timer = this.schedule(() => {
      // The key can be released between the timer firing and this running.
      if (held === null || this.key !== held) return;
      this.fire(held);
      this.arm();
    }, repeatDelayMs(this.count, this.delayMs));
  }
}
