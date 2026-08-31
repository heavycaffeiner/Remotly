// Per-session event deduplication.
//
// The daemon numbers each session's events with a monotonically increasing
// sequence starting at 1, and broadcasts every event to all of the daemon's
// connections. An app that reconnects (or holds two connections to one
// daemon, which the protocol allows) can observe the same event twice. The
// app accepts an event only when its sequence is greater than the last one
// seen for that session.
//
// Replayed scrollback never generates events on the daemon side, so replay
// cannot produce duplicates through the normal path; this deduper covers
// transport-level redelivery and multi-connection overlap.
//
// State is bounded: per-session entries expire after a TTL so a long-lived
// app does not retain a record for every session it has ever seen.
export class EventDeduper {
  private readonly lastSeen = new Map<string, { seq: number; at: number }>();
  private readonly ttlMs: number;
  private readonly now: () => number;

  constructor(now: () => number = Date.now, ttlMs = 10 * 60 * 1000) {
    this.now = now;
    this.ttlMs = ttlMs;
  }

  // Returns true when the event should be processed (its sequence is newer
  // than the last one accepted for the session), false when it is a
  // duplicate or an out-of-order older event.
  accept(sessionId: string, seq: number): boolean {
    if (typeof sessionId !== 'string' || sessionId === '') return false;
    if (!Number.isInteger(seq) || seq <= 0) return false;
    const t = this.now();
    this.prune(t);
    const last = this.lastSeen.get(sessionId);
    if (last !== undefined && seq <= last.seq) return false;
    this.lastSeen.set(sessionId, { seq, at: t });
    return true;
  }

  // The last accepted sequence for a session, or 0 when none.
  lastSeq(sessionId: string): number {
    return this.lastSeen.get(sessionId)?.seq ?? 0;
  }

  private prune(t: number): void {
    for (const [id, e] of this.lastSeen) {
      if (t - e.at > this.ttlMs) this.lastSeen.delete(id);
    }
  }
}
