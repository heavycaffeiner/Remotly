import { describe, expect, it } from '@jest/globals';
import { EventDeduper } from '../events';

// Session ids are daemon-minted hex strings.
const A = 'a'.repeat(64);
const B = 'b'.repeat(64);

describe('EventDeduper', () => {
  it('accepts the first event of a session', () => {
    const d = new EventDeduper(() => 1000);
    expect(d.accept(A, 1)).toBe(true);
    expect(d.lastSeq(A)).toBe(1);
  });

  it('rejects duplicate and older sequences', () => {
    const d = new EventDeduper(() => 1000);
    expect(d.accept(A, 1)).toBe(true);
    expect(d.accept(A, 1)).toBe(false); // duplicate
    expect(d.accept(A, 0)).toBe(false); // invalid
    expect(d.accept(A, 2)).toBe(true);
    expect(d.accept(A, 1)).toBe(false); // out-of-order older
    expect(d.accept(A, 2)).toBe(false); // duplicate
    expect(d.accept(A, 3)).toBe(true);
  });

  it('tracks sessions independently', () => {
    const d = new EventDeduper(() => 1000);
    expect(d.accept(A, 5)).toBe(true);
    expect(d.accept(B, 1)).toBe(true); // session B starts its own sequence
    expect(d.accept(B, 5)).toBe(true); // not compared to session A
    expect(d.accept(A, 5)).toBe(false);
  });

  it('rejects invalid identifiers', () => {
    const d = new EventDeduper(() => 1000);
    expect(d.accept('', 1)).toBe(false);
    expect(d.accept(A, 0)).toBe(false);
    expect(d.accept(A, -2)).toBe(false);
    expect(d.accept(A, 1.5)).toBe(false);
  });

  it('expires entries after the TTL so sequences can restart', () => {
    let now = 1000;
    const d = new EventDeduper(() => now, 5000);
    expect(d.accept(A, 3)).toBe(true);
    now = 3000;
    expect(d.accept(A, 3)).toBe(false); // still within TTL
    now = 9001; // past TTL
    expect(d.accept(A, 1)).toBe(true); // fresh sequence after expiry
  });

  it('prunes only expired entries', () => {
    let now = 1000;
    const d = new EventDeduper(() => now, 5000);
    d.accept(A, 3);
    d.accept(B, 7);
    now = 6001;
    expect(d.accept(B, 7)).toBe(true); // re-accepted after expiry
    expect(d.lastSeq(A)).toBe(0); // expired and pruned
  });
});
