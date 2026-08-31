package pairing

import "encoding/base64"

// encodeB64 is the on-disk encoding for key material in tests.
func encodeB64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
