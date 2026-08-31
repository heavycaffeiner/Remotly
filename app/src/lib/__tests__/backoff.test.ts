import { describe, expect, it } from '@jest/globals';
import { Backoff } from '../backoff';

// Deterministic random sources.
const randLow = () => 0;
const randHigh = () => 1;
const randMid = () => 0.5;

describe('Backoff', () => {
  it('grows exponentially from the base delay', () => {
    const b = new Backoff({
      baseMs: 100,
      capMs: 10000,
      jitter: 0,
      random: randMid,
    });
    expect(b.next()).toBe(100); // 100 * 2^0
    expect(b.next()).toBe(200); // 100 * 2^1
    expect(b.next()).toBe(400);
    expect(b.next()).toBe(800);
    expect(b.count).toBe(4);
  });

  it('caps at the maximum delay', () => {
    const b = new Backoff({
      baseMs: 100,
      capMs: 500,
      jitter: 0,
      random: randMid,
    });
    const delays = [b.next(), b.next(), b.next(), b.next(), b.next()];
    expect(delays).toEqual([100, 200, 400, 500, 500]);
  });

  it('applies jitter within the expected range', () => {
    // With jitter 0.5, delay lands in [0.5*exp, exp].
    const low = new Backoff({
      baseMs: 100,
      capMs: 10000,
      jitter: 0.5,
      random: randLow,
    });
    expect(low.next()).toBe(50);
    const high = new Backoff({
      baseMs: 100,
      capMs: 10000,
      jitter: 0.5,
      random: randHigh,
    });
    expect(high.next()).toBe(100);
    // No jitter: the full exponential value.
    const none = new Backoff({
      baseMs: 100,
      capMs: 10000,
      jitter: 0,
      random: randLow,
    });
    expect(none.next()).toBe(100);
  });

  it('jitter is bounded even at the cap', () => {
    const b = new Backoff({
      baseMs: 100,
      capMs: 1000,
      jitter: 0.5,
      random: randLow,
    });
    for (let i = 0; i < 8; i++) b.next();
    expect(b.next()).toBe(500); // capped at 1000, jittered to 50%
  });

  it('reset starts a fresh sequence', () => {
    const b = new Backoff({
      baseMs: 100,
      capMs: 10000,
      jitter: 0,
      random: randMid,
    });
    b.next();
    b.next();
    expect(b.count).toBe(2);
    b.reset();
    expect(b.count).toBe(0);
    expect(b.next()).toBe(100);
  });

  it('uses safe defaults', () => {
    const b = new Backoff();
    const first = b.next();
    expect(first).toBeGreaterThanOrEqual(250); // 500 * (1 - 0.5)
    expect(first).toBeLessThanOrEqual(500);
  });
});
