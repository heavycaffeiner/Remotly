package pairing

import (
	"bytes"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Device store bounds. The store is small by construction; these caps keep the
// file bounded even under a flood of pair/revoke cycles.
const (
	maxDevices    = 1024 // active + revoked records
	maxRevoked    = 256  // revoked tombstones retained
	maxDeviceName = 100  // matches protocol.md device_name limit
)

// Errors returned by the device store. Callers match them with errors.Is and
// map them to the protocol close reasons: ErrDeviceUnknown -> device_unknown,
// ErrDeviceRevoked -> device_revoked, ErrDeviceDuplicate -> device_duplicate.
var (
	ErrDeviceUnknown   = errors.New("pairing: device unknown")
	ErrDeviceRevoked   = errors.New("pairing: device revoked")
	ErrDeviceDuplicate = errors.New("pairing: device already paired")
	ErrDeviceLimit     = errors.New("pairing: device store limit reached")
)

// Device is one paired-device record. Public is the app's long-term X25519
// public key, which the app presents in hello and which the daemon pins.
// Revoked devices are retained as tombstones so a revoked key keeps failing
// Verify and cannot be re-paired, until evicted by the tombstone bound.
type Device struct {
	Public    [32]byte
	Name      string
	PairedAt  time.Time
	Revoked   bool
	RevokedAt *time.Time
}

type deviceFile struct {
	Version int         `json:"version"`
	Devices []deviceRec `json:"devices"`
}

type deviceRec struct {
	Public    string     `json:"public"`
	Name      string     `json:"name"`
	PairedAt  time.Time  `json:"paired_at"`
	Revoked   bool       `json:"revoked"`
	RevokedAt *time.Time `json:"revoked_at"`
}

// DeviceStore persists paired devices under dir/devices.json with 0600
// permissions and atomic replacement. It is safe for concurrent use.
type DeviceStore struct {
	path    string
	mu      sync.Mutex
	now     func() time.Time
	devices map[[32]byte]*Device
}

func devicesPath(dir string) string { return filepath.Join(dir, "devices.json") }

// LoadDeviceStore opens the device store at dir/devices.json, creating an
// empty store (without writing) if the file is absent. A corrupt file is an
// error, not a silent reset: wiping device records would lock out every paired
// app, so the daemon fails to start instead.
func LoadDeviceStore(dir string) (*DeviceStore, error) {
	s := &DeviceStore{
		path:    devicesPath(dir),
		now:     time.Now,
		devices: make(map[[32]byte]*Device),
	}
	var f deviceFile
	err := readJSONFile(s.path, maxStateBytes, &f)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if f.Version != 1 {
		return nil, fmt.Errorf("pairing: unsupported device store version %d (want 1)", f.Version)
	}
	for _, d := range f.Devices {
		pub, err := base64.RawURLEncoding.DecodeString(d.Public)
		if err != nil || len(pub) != 32 {
			return nil, errors.New("pairing: corrupt device public key")
		}
		var pubArr [32]byte
		copy(pubArr[:], pub)
		dev := &Device{
			Public:   pubArr,
			Name:     d.Name,
			PairedAt: d.PairedAt,
			Revoked:  d.Revoked,
		}
		if d.RevokedAt != nil {
			ra := *d.RevokedAt
			dev.RevokedAt = &ra
		}
		if _, exists := s.devices[pubArr]; exists {
			return nil, errors.New("pairing: duplicate device record")
		}
		s.devices[pubArr] = dev
	}
	return s, nil
}

// Pair records a newly paired device. It rejects a key that is already paired
// (ErrDeviceDuplicate) or revoked (ErrDeviceRevoked), and rejects when the
// store is at capacity (ErrDeviceLimit). On success it persists the store.
func (s *DeviceStore) Pair(pub [32]byte, name string) (Device, error) {
	if err := validateName(name, maxDeviceName); err != nil {
		return Device{}, fmt.Errorf("pairing: device name: %w", err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, ok := s.devices[pub]; ok {
		if existing.Revoked {
			return Device{}, ErrDeviceRevoked
		}
		return Device{}, ErrDeviceDuplicate
	}
	if len(s.devices) >= maxDevices {
		return Device{}, ErrDeviceLimit
	}
	now := s.now()
	dev := &Device{Public: pub, Name: name, PairedAt: now}
	s.devices[pub] = dev
	if err := s.saveLocked(); err != nil {
		delete(s.devices, pub)
		return Device{}, err
	}
	return *dev, nil
}

// Verify reports whether pub is a currently valid paired device. It is the
// gate the transport layer runs in IK mode. Unknown keys return
// ErrDeviceUnknown and revoked keys return ErrDeviceRevoked.
func (s *DeviceStore) Verify(pub [32]byte) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[pub]
	if !ok {
		return Device{}, ErrDeviceUnknown
	}
	if d.Revoked {
		return Device{}, ErrDeviceRevoked
	}
	return *d, nil
}

// Revoke marks a device revoked. It is idempotent: revoking an already
// revoked device succeeds, and revoking an unknown device fails. The revoked
// record is retained as a tombstone so the key keeps failing Verify.
func (s *DeviceStore) Revoke(pub [32]byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.devices[pub]
	if !ok {
		return ErrDeviceUnknown
	}
	if d.Revoked {
		return nil
	}
	now := s.now()
	d.Revoked = true
	d.RevokedAt = &now
	s.trimRevokedLocked()
	if err := s.saveLocked(); err != nil {
		d.Revoked = false
		d.RevokedAt = nil
		return err
	}
	return nil
}

// List returns the active (non-revoked) devices, oldest first.
func (s *DeviceStore) List() []Device {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Device, 0, len(s.devices))
	for _, d := range s.devices {
		if !d.Revoked {
			out = append(out, *d)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].PairedAt.Equal(out[j].PairedAt) {
			return bytes.Compare(out[i].Public[:], out[j].Public[:]) < 0
		}
		return out[i].PairedAt.Before(out[j].PairedAt)
	})
	return out
}

// ActiveCount reports how many devices are paired and not revoked. The LAN
// listener gate uses it (with the token manager) to decide exposure.
func (s *DeviceStore) ActiveCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, d := range s.devices {
		if !d.Revoked {
			n++
		}
	}
	return n
}

// trimRevokedLocked evicts the oldest revoked tombstones beyond maxRevoked.
// Callers hold s.mu.
func (s *DeviceStore) trimRevokedLocked() {
	revoked := make([]*Device, 0, len(s.devices))
	for _, d := range s.devices {
		if d.Revoked {
			revoked = append(revoked, d)
		}
	}
	if len(revoked) <= maxRevoked {
		return
	}
	sort.Slice(revoked, func(i, j int) bool {
		return revoked[i].RevokedAt.Before(*revoked[j].RevokedAt)
	})
	excess := len(revoked) - maxRevoked
	for i := 0; i < excess; i++ {
		delete(s.devices, revoked[i].Public)
	}
}

// saveLocked persists the current records atomically. Callers hold s.mu.
func (s *DeviceStore) saveLocked() error {
	keys := make([][32]byte, 0, len(s.devices))
	for k := range s.devices {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool {
		return bytes.Compare(keys[i][:], keys[j][:]) < 0
	})
	f := deviceFile{Version: 1, Devices: make([]deviceRec, 0, len(keys))}
	for _, k := range keys {
		d := s.devices[k]
		rec := deviceRec{
			Public:   base64.RawURLEncoding.EncodeToString(d.Public[:]),
			Name:     d.Name,
			PairedAt: d.PairedAt,
			Revoked:  d.Revoked,
		}
		if d.RevokedAt != nil {
			ra := *d.RevokedAt
			rec.RevokedAt = &ra
		}
		f.Devices = append(f.Devices, rec)
	}
	return writeJSONAtomic(s.path, f)
}
