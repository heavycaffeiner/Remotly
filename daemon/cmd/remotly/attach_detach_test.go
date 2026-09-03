package main

import (
	"bytes"
	"testing"
)

// The detach sequence must never eat input that only looks like it.
//
// Ctrl-B is a prefix in tmux, so a lone Ctrl-B has to reach the session intact.
// Only Ctrl-B immediately followed by d detaches.
func TestFilterDetach(t *testing.T) {
	cases := []struct {
		name       string
		in         []byte
		armed      bool
		wantOut    []byte
		wantDetach bool
		wantArmed  bool
	}{
		{
			name:    "plain input passes through",
			in:      []byte("ls -la\r"),
			wantOut: []byte("ls -la\r"),
		},
		{
			name:       "sequence detaches and is not forwarded",
			in:         []byte{detachLead, 'd'},
			wantDetach: true,
		},
		{
			name:       "sequence with uppercase D detaches",
			in:         []byte{detachLead, 'D'},
			wantDetach: true,
		},
		{
			name:       "sequence with Ctrl-D detaches",
			in:         []byte{detachLead, 0x04},
			wantDetach: true,
		},
		{
			name:    "lone prefix at end arms for the next chunk",
			in:      []byte{'a', detachLead},
			wantOut: []byte{'a'},
			// Held back: the next byte decides whether it was a detach.
			wantArmed: true,
		},
		{
			name:       "armed prefix completes across chunks",
			in:         []byte{'d'},
			armed:      true,
			wantDetach: true,
		},
		{
			name:    "prefix followed by other input is forwarded in order",
			in:      []byte{detachLead, 'x'},
			wantOut: []byte{detachLead, 'x'},
		},
		{
			name:    "armed prefix not completed is forwarded first",
			in:      []byte{'x'},
			armed:   true,
			wantOut: []byte{detachLead, 'x'},
		},
		{
			name:      "double prefix emits one and stays armed",
			in:        []byte{detachLead, detachLead},
			wantOut:   []byte{detachLead},
			wantArmed: true,
		},
		{
			name:       "prefix then prefix then d detaches",
			in:         []byte{detachLead, detachLead, 'd'},
			wantOut:    []byte{detachLead},
			wantDetach: true,
		},
		{
			name:    "input before the sequence is kept",
			in:      append([]byte("echo hi\r"), detachLead, 'd'),
			wantOut: []byte("echo hi\r"),
			// The detach ends the chunk; everything typed before it still
			// reaches the session.
			wantDetach: true,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out, detach, armed := filterDetach(c.in, c.armed)
			if !bytes.Equal(out, c.wantOut) && !(len(out) == 0 && len(c.wantOut) == 0) {
				t.Errorf("out = %q, want %q", out, c.wantOut)
			}
			if detach != c.wantDetach {
				t.Errorf("detach = %v, want %v", detach, c.wantDetach)
			}
			if armed != c.wantArmed {
				t.Errorf("armed = %v, want %v", armed, c.wantArmed)
			}
		})
	}
}

func TestShortID(t *testing.T) {
	long := "0123456789abcdef0123456789abcdef"
	if got := shortID(long); got != "0123456789ab" {
		t.Errorf("shortID = %q", got)
	}
	if got := shortID("abc"); got != "abc" {
		t.Errorf("short input = %q, want unchanged", got)
	}
}
