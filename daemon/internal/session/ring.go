package session

import "bytes"

// maxLineBytes caps the average line length used to derive the byte cap.
// The byte cap is a safety valve against a stream of very long lines;
// normal operation is bounded by the line cap.
const (
	maxLineBytes = 128
	minRingBytes = 4 << 20
	maxRingBytes = 32 << 20
)

// ring is a bounded in-memory scrollback for one terminal byte stream.
// It retains at most lineCap complete lines and byteCap total bytes. A
// trailing partial line (no terminating newline) is always retained.
//
// Eviction order: complete lines are dropped from the front first, cut at
// newline boundaries; if the stream still exceeds byteCap (for example one
// giant line), bytes are dropped from the front mid-line. Memory is bounded
// in both cases.
//
// The ring is not safe for concurrent use. The session's drain goroutine is
// its sole writer; readers hold the session lock while taking snapshots.
type ring struct {
	lineCap int
	byteCap int

	chunks     []ringChunk
	totalBytes int
	totalLines int
}

type ringChunk struct {
	data  []byte
	lines int // newline count within data
}

func newRing(lineCap, byteCap int) *ring {
	if byteCap < minRingBytes {
		byteCap = minRingBytes
	}
	if byteCap > maxRingBytes {
		byteCap = maxRingBytes
	}
	return &ring{lineCap: lineCap, byteCap: byteCap}
}

// derivedByteCap maps a configured line cap to a byte safety valve.
func derivedByteCap(lineCap int) int {
	bc := lineCap * maxLineBytes
	if bc < minRingBytes {
		bc = minRingBytes
	}
	if bc > maxRingBytes {
		bc = maxRingBytes
	}
	return bc
}

// append retains b, copying it, and evicts to the caps.
func (r *ring) append(b []byte) {
	if len(b) == 0 {
		return
	}
	buf := make([]byte, len(b))
	copy(buf, b)
	r.chunks = append(r.chunks, ringChunk{data: buf, lines: bytes.Count(buf, []byte{'\n'})})
	r.totalBytes += len(buf)
	r.totalLines += r.chunks[len(r.chunks)-1].lines
	r.evictLines()
	r.evictBytes()
}

// evictLines drops complete lines from the front until totalLines is within
// lineCap. A line is the bytes up to and including its terminating newline.
func (r *ring) evictLines() {
	for r.totalLines > r.lineCap && len(r.chunks) > 0 {
		need := r.totalLines - r.lineCap
		c := &r.chunks[0]
		if c.lines < need {
			// The whole front chunk is dropped.
			need -= c.lines
			r.totalLines -= c.lines
			r.totalBytes -= len(c.data)
			r.chunks = r.chunks[1:]
			continue
		}
		// Cut this chunk after its need-th newline.
		idx := -1
		for i := 0; i < need; i++ {
			j := bytes.IndexByte(c.data[idx+1:], '\n')
			if j < 0 {
				idx = -1
				break
			}
			idx += 1 + j
		}
		if idx < 0 {
			break
		}
		c.data = c.data[idx+1:]
		c.lines -= need
		r.totalLines -= need
		r.totalBytes -= idx + 1
		if len(c.data) == 0 {
			r.chunks = r.chunks[1:]
		}
	}
}

// evictBytes drops bytes from the front, possibly mid-line, until totalBytes
// is within byteCap.
func (r *ring) evictBytes() {
	for r.totalBytes > r.byteCap && len(r.chunks) > 0 {
		c := &r.chunks[0]
		drop := r.totalBytes - r.byteCap
		if drop >= len(c.data) {
			r.totalBytes -= len(c.data)
			r.totalLines -= c.lines
			r.chunks = r.chunks[1:]
			continue
		}
		c.data = c.data[drop:]
		// Recount lines in the remainder.
		c.lines = bytes.Count(c.data, []byte{'\n'})
		r.totalBytes -= drop
		if len(c.data) == 0 {
			r.chunks = r.chunks[1:]
		}
	}
}

// snapshotFrom returns a copy of the retained stream from byte offset off
// (relative to the retained stream's start) to its end. Offsets at or beyond
// the retained bytes yield an empty result.
func (r *ring) snapshotFrom(off int) []byte {
	if r.totalBytes == 0 || off >= r.totalBytes {
		return nil
	}
	out := make([]byte, 0, r.totalBytes-off)
	remaining := off
	for i := range r.chunks {
		c := &r.chunks[i]
		if remaining >= len(c.data) {
			remaining -= len(c.data)
			continue
		}
		out = append(out, c.data[remaining:]...)
		remaining = 0
	}
	return out
}

// tail returns a copy of the last n retained bytes (or the whole retained
// stream when it is shorter). Preview and event text are derived from it.
func (r *ring) tail(n int) []byte {
	if n <= 0 || r.totalBytes == 0 {
		return nil
	}
	if n > r.totalBytes {
		n = r.totalBytes
	}
	return r.snapshotFrom(r.totalBytes - n)
}

// snapshot returns a copy of the retained stream, oldest first.
func (r *ring) snapshot() []byte {
	if r.totalBytes == 0 {
		return nil
	}
	out := make([]byte, 0, r.totalBytes)
	for i := range r.chunks {
		out = append(out, r.chunks[i].data...)
	}
	return out
}

func (r *ring) len() int { return r.totalBytes }
