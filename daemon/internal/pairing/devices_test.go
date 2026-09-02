package pairing

import (
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var deviceClockBase = time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)

func testDeviceStore(t *testing.T) *DeviceStore {
	t.Helper()
	s, err := LoadDeviceStore(t.TempDir())
	if err != nil {
		t.Fatalf("LoadDeviceStore: %v", err)
	}
	return s
}

func pubByte(n int) [32]byte {
	var p [32]byte
	p[0] = byte(n)
	p[1] = byte(n >> 8)
	return p
}

func TestDevicePairAndVerify(t *testing.T) {
	s := testDeviceStore(t)
	s.now = func() time.Time { return deviceClockBase }
	pub := pubByte(1)

	dev, err := s.Pair(pub, "phone")
	if err != nil {
		t.Fatalf("Pair: %v", err)
	}
	if dev.Public != pub || dev.Name != "phone" || dev.Revoked {
		t.Fatalf("unexpected device: %+v", dev)
	}
	if !dev.PairedAt.Equal(deviceClockBase) {
		t.Fatalf("paired_at = %v, want the store clock", dev.PairedAt)
	}

	got, err := s.Verify(pub)
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if got.Public != pub || got.Name != "phone" {
		t.Fatalf("Verify returned wrong device: %+v", got)
	}

	list := s.List()
	if len(list) != 1 || list[0].Public != pub {
		t.Fatalf("List = %+v, want one device", list)
	}
	if got := s.ActiveCount(); got != 1 {
		t.Fatalf("ActiveCount = %d, want 1", got)
	}
}

// Re-pairing a device that is already paired refreshes it instead of failing.
// A user who reinstalled the app scans a fresh token with the same key, and
// rejecting that left them with no way back in.
func TestDeviceRepairRefreshes(t *testing.T) {
	s := testDeviceStore(t)
	pub := pubByte(1)
	first, err := s.Pair(pub, "phone")
	if err != nil {
		t.Fatal(err)
	}
	again, err := s.Pair(pub, "phone-2")
	if err != nil {
		t.Fatalf("re-pair = %v, want success", err)
	}
	if again.Name != "phone-2" {
		t.Fatalf("name = %q, want the name from the new pairing", again.Name)
	}
	if again.PairedAt.Before(first.PairedAt) {
		t.Fatal("re-pair moved PairedAt backwards")
	}
	// Still one device: the same key must not be stored twice.
	if got := s.ActiveCount(); got != 1 {
		t.Fatalf("ActiveCount = %d, want 1", got)
	}
	if got, err := s.Verify(pub); err != nil || got.Name != "phone-2" {
		t.Fatalf("Verify after re-pair = %+v, %v", got, err)
	}
}

// A revoked device must not be able to re-pair itself.
func TestDeviceRevokedCannotRepair(t *testing.T) {
	s := testDeviceStore(t)
	pub := pubByte(1)
	if _, err := s.Pair(pub, "phone"); err != nil {
		t.Fatal(err)
	}
	if err := s.Revoke(pub); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pair(pub, "phone"); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("re-pair after revoke = %v, want ErrDeviceRevoked", err)
	}
}

func TestDeviceRevokeFlow(t *testing.T) {
	s := testDeviceStore(t)
	pub := pubByte(1)
	if _, err := s.Pair(pub, "phone"); err != nil {
		t.Fatal(err)
	}

	if err := s.Revoke(pub); err != nil {
		t.Fatalf("Revoke: %v", err)
	}
	if _, err := s.Verify(pub); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("Verify(revoked) = %v, want ErrDeviceRevoked", err)
	}
	if got := s.ActiveCount(); got != 0 {
		t.Fatalf("ActiveCount = %d, want 0", got)
	}
	if list := s.List(); len(list) != 0 {
		t.Fatalf("List = %+v, want empty", list)
	}
	// Idempotent.
	if err := s.Revoke(pub); err != nil {
		t.Fatalf("second Revoke: %v", err)
	}
	// A revoked key cannot be re-paired while the tombstone exists.
	if _, err := s.Pair(pub, "phone-2"); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("Pair(revoked) = %v, want ErrDeviceRevoked", err)
	}
}

func TestDeviceRevokeAndVerifyUnknown(t *testing.T) {
	s := testDeviceStore(t)
	var unknown [32]byte
	if err := s.Revoke(unknown); !errors.Is(err, ErrDeviceUnknown) {
		t.Fatalf("Revoke(unknown) = %v, want ErrDeviceUnknown", err)
	}
	if _, err := s.Verify(unknown); !errors.Is(err, ErrDeviceUnknown) {
		t.Fatalf("Verify(unknown) = %v, want ErrDeviceUnknown", err)
	}
}

func TestDeviceUnrelatedSurvivesRevoke(t *testing.T) {
	s := testDeviceStore(t)
	a, b := pubByte(1), pubByte(2)
	if _, err := s.Pair(a, "a"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pair(b, "b"); err != nil {
		t.Fatal(err)
	}
	if err := s.Revoke(a); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Verify(b); err != nil {
		t.Fatalf("Verify(unrelated) = %v, want ok", err)
	}
}

func TestDevicePersistence(t *testing.T) {
	dir := t.TempDir()
	s, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	a, b := pubByte(1), pubByte(2)
	if _, err := s.Pair(a, "phone"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pair(b, "tablet"); err != nil {
		t.Fatal(err)
	}
	if err := s.Revoke(a); err != nil {
		t.Fatal(err)
	}

	fi, err := os.Stat(filepath.Join(dir, "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	if perm := fi.Mode().Perm(); perm != stateFileMode {
		t.Fatalf("devices.json mode = %o, want %o", perm, stateFileMode)
	}

	// Reload from disk.
	s2, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if _, err := s2.Verify(a); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("Verify(revoked, reloaded) = %v, want ErrDeviceRevoked", err)
	}
	dev, err := s2.Verify(b)
	if err != nil {
		t.Fatalf("Verify(active, reloaded) = %v", err)
	}
	if dev.Name != "tablet" {
		t.Fatalf("reloaded name = %q, want tablet", dev.Name)
	}
	if got := s2.ActiveCount(); got != 1 {
		t.Fatalf("reloaded ActiveCount = %d, want 1", got)
	}
}

func TestDeviceLoadCorrupt(t *testing.T) {
	dir := t.TempDir()
	corrupt := []byte("not json at all\n")
	if err := os.WriteFile(filepath.Join(dir, "devices.json"), corrupt, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadDeviceStore(dir); err == nil {
		t.Fatal("expected error for corrupt device store")
	}
}

func TestDeviceLoadBadRecords(t *testing.T) {
	cases := []struct {
		name string
		file deviceFile
	}{
		{"bad version", deviceFile{Version: 2}},
		{"short public key", deviceFile{
			Version: 1,
			Devices: []deviceRec{{Public: "AAAA", Name: "x", PairedAt: deviceClockBase}},
		}},
		{"duplicate record", func() deviceFile {
			pub := pubByte(1)
			return deviceFile{
				Version: 1,
				Devices: []deviceRec{
					{Public: encodeB64(pub[:]), Name: "x", PairedAt: deviceClockBase},
					{Public: encodeB64(pub[:]), Name: "y", PairedAt: deviceClockBase},
				},
			}
		}()},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := writeJSONAtomic(filepath.Join(dir, "devices.json"), tc.file); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadDeviceStore(dir); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

func TestDeviceNameValidation(t *testing.T) {
	s := testDeviceStore(t)
	i := 0
	cases := []struct {
		name  string
		value string
		want  bool
	}{
		{"empty", "", false},
		{"100 bytes", strings.Repeat("n", 100), true},
		{"101 bytes", strings.Repeat("n", 101), false},
		{"nul byte", "a\x00b", false},
		{"del byte", "a\x7fb", false},
		{"newline", "a\nb", false},
		{"unicode", "폰-1", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// A distinct key per case: Pair rejects a repeat public key,
			// which would mask the name-validation result.
			i++
			pub := pubByte(i)
			_, err := s.Pair(pub, tc.value)
			if tc.want && err != nil {
				t.Fatalf("Pair(%q): %v", tc.value, err)
			}
			if !tc.want && err == nil {
				t.Fatalf("Pair(%q) accepted, want error", tc.value)
			}
		})
	}
}

// TestDeviceTombstoneTrim evicts the oldest revoked records once the tombstone
// bound is exceeded.
func TestDeviceTombstoneTrim(t *testing.T) {
	dir := t.TempDir()
	s, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	var at time.Time = deviceClockBase
	s.now = func() time.Time {
		at = at.Add(time.Second)
		return at
	}

	const n = maxRevoked + 1 // 257
	pubs := make([][32]byte, n)
	for i := range pubs {
		pubs[i] = pubByte(i + 1)
		if _, err := s.Pair(pubs[i], "d"); err != nil {
			t.Fatalf("Pair(%d): %v", i, err)
		}
	}
	for i, p := range pubs {
		if err := s.Revoke(p); err != nil {
			t.Fatalf("Revoke(%d): %v", i, err)
		}
	}

	// The oldest tombstone (first paired) was evicted; the newest remains.
	if _, err := s.Verify(pubs[0]); !errors.Is(err, ErrDeviceUnknown) {
		t.Fatalf("Verify(oldest) = %v, want ErrDeviceUnknown (evicted)", err)
	}
	if _, err := s.Verify(pubs[n-1]); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("Verify(newest) = %v, want ErrDeviceRevoked", err)
	}
	if got := s.ActiveCount(); got != 0 {
		t.Fatalf("ActiveCount = %d, want 0", got)
	}

	// The trim is persisted, not just in memory.
	s2, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s2.Verify(pubs[0]); !errors.Is(err, ErrDeviceUnknown) {
		t.Fatalf("reloaded Verify(oldest) = %v, want ErrDeviceUnknown", err)
	}
	if _, err := s2.Verify(pubs[n-1]); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("reloaded Verify(newest) = %v, want ErrDeviceRevoked", err)
	}
}

func TestDeviceLimit(t *testing.T) {
	dir := t.TempDir()
	// Pre-fill the store to capacity with a direct file write, which is far
	// cheaper than 1024 Pair calls.
	f := deviceFile{Version: 1, Devices: make([]deviceRec, 0, maxDevices)}
	for i := 0; i < maxDevices; i++ {
		pub := pubByte(i)
		f.Devices = append(f.Devices, deviceRec{
			Public:   encodeB64(pub[:]),
			Name:     "d",
			PairedAt: deviceClockBase,
		})
	}
	if err := writeJSONAtomic(filepath.Join(dir, "devices.json"), f); err != nil {
		t.Fatal(err)
	}
	s, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := s.ActiveCount(); got != maxDevices {
		t.Fatalf("ActiveCount = %d, want %d", got, maxDevices)
	}
	var fresh [32]byte
	fresh[31] = 0xff
	if _, err := s.Pair(fresh, "over"); !errors.Is(err, ErrDeviceLimit) {
		t.Fatalf("Pair at capacity = %v, want ErrDeviceLimit", err)
	}
}

func TestDeviceListOrdering(t *testing.T) {
	// List never persists, so a store with no path is enough to test it.
	s := &DeviceStore{
		now:     func() time.Time { return deviceClockBase },
		devices: make(map[[32]byte]*Device),
	}
	a, b, c := pubByte(10), pubByte(2), pubByte(20)
	s.devices[a] = &Device{Public: a, Name: "a", PairedAt: deviceClockBase}
	s.devices[b] = &Device{Public: b, Name: "b", PairedAt: deviceClockBase.Add(time.Second)}
	cAt := deviceClockBase.Add(2 * time.Second)
	s.devices[c] = &Device{Public: c, Name: "c", PairedAt: cAt, Revoked: true}

	got := s.List()
	if len(got) != 2 {
		t.Fatalf("List = %d entries, want 2 (revoked excluded)", len(got))
	}
	if got[0].Public != a || got[1].Public != b {
		t.Fatalf("List order = [%x %x], want [a b]", got[0].Public[0], got[1].Public[0])
	}
}

func TestDeviceB64RoundTripInFile(t *testing.T) {
	dir := t.TempDir()
	s, err := LoadDeviceStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	pub := pubByte(7)
	if _, err := s.Pair(pub, "phone"); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	want := base64.RawURLEncoding.EncodeToString(pub[:])
	if !strings.Contains(string(raw), want) {
		t.Fatalf("devices.json does not contain %q", want)
	}
}
