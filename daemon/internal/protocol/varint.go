package protocol

// maxVarintBytes bounds LEB128 decoding so a crafted prefix can never
// allocate an unbounded integer. 5 bytes covers uint32, the widest field.
const maxVarintBytes = 5

// AppendVarint encodes v as unsigned LEB128. Callers must keep v < 2^32.
func AppendVarint(dst []byte, v uint64) []byte {
	for {
		b := byte(v & 0x7f)
		v >>= 7
		if v != 0 {
			b |= 0x80
		}
		dst = append(dst, b)
		if v == 0 {
			return dst
		}
	}
}

// ReadVarint decodes one unsigned LEB128 from the front of b. It reports the
// value and the number of bytes consumed.
func ReadVarint(b []byte) (uint64, int, error) {
	var v uint64
	for i := 0; i < len(b); i++ {
		if i == maxVarintBytes {
			return 0, 0, ErrVarintTooLong
		}
		c := b[i]
		v |= uint64(c&0x7f) << (7 * i)
		if c&0x80 == 0 {
			return v, i + 1, nil
		}
	}
	return 0, 0, ErrVarintTruncated
}
