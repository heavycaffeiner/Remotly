// Bounded exponential backoff with jitter for reconnect attempts.
//
// After each failed attempt the delay grows exponentially (base * 2^n) until
// it hits the cap, and jitter randomizes it so several apps reconnecting to
// the same daemon do not stampede. The caller supplies its own random source
// for determinism in tests.
//
// Cancellation is the caller's job: a new attempt sequence (user action,
// host removal, background transition) resets the counter.
export interface BackoffOptions {
  /** Delay of the first retry, in milliseconds. Default 500. */
  baseMs?: number;
  /** Maximum delay, in milliseconds. Default 30000. */
  capMs?: number;
  /** Jitter fraction in [0, 1). 0.5 means each delay lands in [50%, 100%]
   * of its exponential value. Default 0.5. */
  jitter?: number;
  /** Random source in [0, 1). Default Math.random. */
  random?: () => number;
}

export class Backoff {
  private readonly baseMs: number;
  private readonly capMs: number;
  private readonly jitter: number;
  private readonly random: () => number;
  private attempts = 0;

  constructor(opts: BackoffOptions = {}) {
    this.baseMs = opts.baseMs ?? 500;
    this.capMs = opts.capMs ?? 30000;
    this.jitter = opts.jitter ?? 0.5;
    this.random = opts.random ?? Math.random;
  }

  // The number of failed attempts so far in this sequence.
  get count(): number {
    return this.attempts;
  }

  // Records one failed attempt and returns the delay to wait before the next
  // one. The exponential value is base * 2^(attempts-1), capped, then
  // jittered into [delay*(1-jitter), delay].
  next(): number {
    const exp = Math.min(this.capMs, this.baseMs * 2 ** this.attempts);
    this.attempts += 1;
    const r = this.random();
    const jittered = exp * (1 - this.jitter + this.jitter * r);
    return Math.max(0, Math.round(jittered));
  }

  // Starts a fresh sequence (successful connect, or the user giving up and
  // retrying by hand).
  reset(): void {
    this.attempts = 0;
  }
}
