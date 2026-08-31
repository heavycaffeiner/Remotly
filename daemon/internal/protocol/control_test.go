package protocol

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
)

var testPub = base64.RawURLEncoding.EncodeToString(make([]byte, 32))
var testSessionID = strings.Repeat("ab", 32)

func TestControlParseValid(t *testing.T) {
	cases := []struct {
		name string
		json string
	}{
		{"hello", `{"id":1,"type":"hello","device_name":"Pixel 9","device_pub":"` + testPub + `"}`},
		{"create shell minimal", `{"id":2,"type":"session.create","kind":"shell"}`},
		{"create shell full", `{"id":2,"type":"session.create","kind":"shell","title":"work","cwd":"/home/u","cols":120,"rows":40}`},
		{"create agent", `{"id":3,"type":"session.create","kind":"agent","command":"claude --resume","cols":80,"rows":24}`},
		{"list", `{"id":4,"type":"session.list"}`},
		{"attach", `{"id":5,"type":"session.attach","session_id":"` + testSessionID + `"}`},
		{"detach", `{"id":6,"type":"session.detach","channel_id":7}`},
		{"resize", `{"id":7,"type":"session.resize","session_id":"` + testSessionID + `","cols":100,"rows":50}`},
		{"kill", `{"id":8,"type":"session.kill","session_id":"` + testSessionID + `"}`},
		{"id zero", `{"id":0,"type":"session.list"}`},
		{"id max", `{"id":9007199254740991,"type":"session.list"}`},
	}
	for _, tc := range cases {
		req, err := Parse([]byte(tc.json))
		if err != nil {
			t.Fatalf("%s: %v", tc.name, err)
		}
		if req.Type == "" || req.ID == 0 && !strings.Contains(tc.json, `"id":0`) {
			t.Fatalf("%s: bad request %+v", tc.name, req)
		}
	}

	// hello fields must be decoded.
	req, err := Parse([]byte(`{"id":1,"type":"hello","device_name":"n","device_pub":"` + testPub + `"}`))
	if err != nil {
		t.Fatal(err)
	}
	if *req.DeviceName != "n" {
		t.Fatalf("device name: %q", *req.DeviceName)
	}
	var decoded [32]byte
	pub, _ := base64.RawURLEncoding.DecodeString(testPub)
	copy(decoded[:], pub)
	if req.DevicePub != decoded {
		t.Fatal("device pub mismatch")
	}
}

func TestControlParseInvalidFields(t *testing.T) {
	cases := []struct {
		name string
		json string
	}{
		{"hello missing name", `{"id":1,"type":"hello","device_pub":"` + testPub + `"}`},
		{"hello missing pub", `{"id":1,"type":"hello","device_name":"n"}`},
		{"hello empty name", `{"id":1,"type":"hello","device_name":"","device_pub":"` + testPub + `"}`},
		{"hello long name", `{"id":1,"type":"hello","device_name":"` + strings.Repeat("a", 101) + `","device_pub":"` + testPub + `"}`},
		{"hello control char name", `{"id":1,"type":"hello","device_name":"a\nb","device_pub":"` + testPub + `"}`},
		{"hello del char name", `{"id":1,"type":"hello","device_name":"a\u007fb","device_pub":"` + testPub + `"}`},
		{"hello short pub", `{"id":1,"type":"hello","device_name":"n","device_pub":"` + base64.RawURLEncoding.EncodeToString(make([]byte, 31)) + `"}`},
		{"hello padded pub", `{"id":1,"type":"hello","device_name":"n","device_pub":"` + base64.URLEncoding.EncodeToString(make([]byte, 32)) + `"}`},
		{"hello extra field", `{"id":1,"type":"hello","device_name":"n","device_pub":"` + testPub + `","cols":80}`},
		{"unknown top field", `{"id":2,"type":"session.list","bogus":1}`},
		{"list with field", `{"id":3,"type":"session.list","cols":80}`},
		{"create missing kind", `{"id":4,"type":"session.create"}`},
		{"create bad kind", `{"id":4,"type":"session.create","kind":"zsh"}`},
		{"create shell command", `{"id":4,"type":"session.create","kind":"shell","command":"ls"}`},
		{"create agent no command", `{"id":4,"type":"session.create","kind":"agent"}`},
		{"create long command", `{"id":4,"type":"session.create","kind":"agent","command":"` + strings.Repeat("x", 4097) + `"}`},
		{"create long title", `{"id":4,"type":"session.create","kind":"shell","title":"` + strings.Repeat("t", 201) + `"}`},
		{"create relative cwd", `{"id":4,"type":"session.create","kind":"shell","cwd":"home/u"}`},
		{"create cols zero", `{"id":4,"type":"session.create","kind":"shell","cols":0}`},
		{"create cols over", `{"id":4,"type":"session.create","kind":"shell","cols":1001}`},
		{"create rows under", `{"id":4,"type":"session.create","kind":"shell","rows":-1}`},
		{"create session id", `{"id":4,"type":"session.create","kind":"shell","session_id":"` + testSessionID + `"}`},
		{"attach short id", `{"id":5,"type":"session.attach","session_id":"` + strings.Repeat("a", 63) + `"}`},
		{"attach upper id", `{"id":5,"type":"session.attach","session_id":"` + strings.ToUpper(testSessionID) + `"}`},
		{"attach nonhex id", `{"id":5,"type":"session.attach","session_id":"` + strings.Repeat("g", 64) + `"}`},
		{"detach missing id", `{"id":6,"type":"session.detach"}`},
		{"resize missing rows", `{"id":7,"type":"session.resize","session_id":"` + testSessionID + `","cols":80}`},
		{"resize cols over", `{"id":7,"type":"session.resize","session_id":"` + testSessionID + `","cols":1001,"rows":24}`},
		{"kill extra field", `{"id":8,"type":"session.kill","session_id":"` + testSessionID + `","cols":80}`},
	}
	for _, tc := range cases {
		if _, err := Parse([]byte(tc.json)); !errors.Is(err, ErrInvalidRequest) {
			t.Fatalf("%s: got %v, want ErrInvalidRequest", tc.name, err)
		}
	}
}

func TestControlParseShape(t *testing.T) {
	cases := []struct {
		name string
		json string
		want error
	}{
		{"missing id", `{"type":"session.list"}`, ErrCtrlFrame},
		{"missing type", `{"id":1}`, ErrCtrlFrame},
		{"id too big", `{"id":9007199254740992,"type":"session.list"}`, ErrCtrlFrame},
		{"id negative", `{"id":-1,"type":"session.list"}`, ErrBadJSON},
		{"id float", `{"id":1.5,"type":"session.list"}`, ErrBadJSON},
		{"id string", `{"id":"1","type":"session.list"}`, ErrBadJSON},
		{"type number", `{"id":1,"type":1}`, ErrBadJSON},
		{"unknown type", `{"id":1,"type":"bogus"}`, ErrUnknownType},
		{"not json", `hello`, ErrBadJSON},
		{"array", `[1,2,3]`, ErrBadJSON},
		{"null", `null`, ErrBadJSON},
		{"trailing", `{"id":1,"type":"session.list"}x`, ErrBadJSON},
		{"empty object", `{}`, ErrCtrlFrame},
	}
	for _, tc := range cases {
		_, err := Parse([]byte(tc.json))
		if !errors.Is(err, tc.want) {
			t.Fatalf("%s: got %v, want %v", tc.name, err, tc.want)
		}
	}
}

func TestControlEncoders(t *testing.T) {
	pub := [32]byte{1, 2, 3}

	r, err := ParseResponse(EncodeHelloResponse(7, "daemon-name", pub))
	if err != nil {
		t.Fatalf("hello: %v", err)
	}
	if r.ID != 7 || r.Type != TypeHello || r.DaemonName != "daemon-name" {
		t.Fatalf("hello: %+v", r)
	}
	if r.DaemonPub != base64.RawURLEncoding.EncodeToString(pub[:]) {
		t.Fatalf("hello pub: %s", r.DaemonPub)
	}

	r, err = ParseResponse(EncodeErrorResponse(8, TypeSessionKill, CodeUnknownSession, "no such session"))
	if err != nil {
		t.Fatalf("error: %v", err)
	}
	if r.Error == nil || r.Error.Code != CodeUnknownSession || r.Error.Message != "no such session" {
		t.Fatalf("error: %+v", r)
	}

	longMsg := strings.Repeat("m", 600)
	r, err = ParseResponse(EncodeErrorResponse(9, TypeHello, CodeNameInvalid, longMsg))
	if err != nil {
		t.Fatalf("clamp: %v", err)
	}
	if len(r.Error.Message) > MaxErrorLen {
		t.Fatalf("clamp length: %d", len(r.Error.Message))
	}

	meta := Meta{
		ID:           testSessionID,
		Title:        "work",
		Kind:         KindShell,
		Command:      "zsh -l",
		Cwd:          "/home/u",
		Cols:         80,
		Rows:         24,
		CreatedAt:    "2026-08-16T00:00:00Z",
		LastActivity: "2026-08-16T01:00:00Z",
		Running:      true,
	}
	r, err = ParseResponse(EncodeCreateResponse(10, meta))
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if r.Session == nil || r.Session.ID != meta.ID || !r.Session.Running || r.Session.Exit != nil {
		t.Fatalf("create: %+v", r)
	}

	r, err = ParseResponse(EncodeListResponse(11, nil))
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if r.Sessions == nil || len(r.Sessions) != 0 {
		t.Fatalf("list: %+v", r)
	}

	r, err = ParseResponse(EncodeListResponse(12, []Meta{meta}))
	if err != nil || r.Sessions == nil || len(r.Sessions) != 1 || r.Sessions[0].ID != meta.ID {
		t.Fatalf("list one: %+v, %v", r, err)
	}

	r, err = ParseResponse(EncodeAttachResponse(13, 5, ContinuityGapless, 42))
	if err != nil {
		t.Fatalf("attach: %v", err)
	}
	if r.ChannelID == nil || *r.ChannelID != 5 {
		t.Fatalf("attach: %+v", r)
	}
	if r.Continuity == nil || *r.Continuity != ContinuityGapless || r.ReplayedFrom == nil || *r.ReplayedFrom != 42 {
		t.Fatalf("attach continuity: %+v", r)
	}

	r, err = ParseResponse(EncodePlainResponse(14, TypeSessionDetach))
	if err != nil {
		t.Fatalf("plain: %v", err)
	}
	if r.Type != TypeSessionDetach || r.ID != 14 {
		t.Fatalf("plain: %+v", r)
	}
}

func TestControlEncodeClose(t *testing.T) {
	for _, reason := range []string{ReasonSessionExited, ReasonOverflow, ReasonDetached, ReasonClosed} {
		data, err := EncodeChannelClose(3, reason)
		if err != nil {
			t.Fatalf("%s: %v", reason, err)
		}
		n, err := ParseNotification(data)
		if err != nil {
			t.Fatalf("%s parse: %v", reason, err)
		}
		if n.Type != TypeChannelClose || *n.ChannelID != 3 || *n.Reason != reason {
			t.Fatalf("%s: %+v", reason, n)
		}
	}
	if _, err := EncodeChannelClose(3, "bogus"); !errors.Is(err, ErrCtrlFrame) {
		t.Fatalf("bogus reason: %v", err)
	}
}

func TestControlParseNotification(t *testing.T) {
	meta := Meta{ID: testSessionID, Running: false, Exit: &Exit{Code: 1, Signal: ""}}
	data := EncodeSessionUpdate(meta)
	n, err := ParseNotification(data)
	if err != nil {
		t.Fatalf("update: %v", err)
	}
	if n.Type != TypeSessionUpdate || n.Session == nil || n.Session.ID != meta.ID {
		t.Fatalf("update: %+v", n)
	}

	cases := []struct {
		name string
		data []byte
		want error
	}{
		{"close missing reason", []byte(`{"type":"channel.close","channel_id":1}`), ErrCtrlFrame},
		{"close missing id", []byte(`{"type":"channel.close","reason":"closed"}`), ErrCtrlFrame},
		{"close bad reason", []byte(`{"type":"channel.close","channel_id":1,"reason":"nope"}`), ErrCtrlFrame},
		{"update missing session", []byte(`{"type":"session.update"}`), ErrCtrlFrame},
		{"no type", []byte(`{"channel_id":1}`), ErrCtrlFrame},
		{"trailing", []byte(`{"type":"channel.close","channel_id":1,"reason":"closed"}x`), ErrBadJSON},
		{"bad json", []byte(`{`), ErrBadJSON},
	}
	for _, tc := range cases {
		if _, err := ParseNotification(tc.data); !errors.Is(err, tc.want) {
			t.Fatalf("%s: got %v, want %v", tc.name, err, tc.want)
		}
	}

	// Unknown notification types pass through for probing.
	n, err = ParseNotification([]byte(`{"type":"future.thing","x":1}`))
	if err != nil || n.Type != "future.thing" {
		t.Fatalf("probe: %+v, %v", n, err)
	}
}

func TestControlParseResponse(t *testing.T) {
	cases := []struct {
		name string
		data []byte
		want error
	}{
		{"missing id", []byte(`{"type":"hello"}`), ErrCtrlFrame},
		{"no type", []byte(`{"id":1}`), ErrCtrlFrame},
		{"trailing", []byte(`{"id":1,"type":"hello"}x`), ErrBadJSON},
		{"bad json", []byte(`nope`), ErrBadJSON},
		{"not object", []byte(`[1]`), ErrBadJSON},
	}
	for _, tc := range cases {
		if _, err := ParseResponse(tc.data); !errors.Is(err, tc.want) {
			t.Fatalf("%s: got %v, want %v", tc.name, err, tc.want)
		}
	}

	// id 0 is a valid response id.
	r, err := ParseResponse([]byte(`{"id":0,"type":"session.list","sessions":[]}`))
	if err != nil || r.ID != 0 || r.Sessions == nil {
		t.Fatalf("id 0: %+v, %v", r, err)
	}
}

func TestIDTracker(t *testing.T) {
	tr := NewIDTracker()
	if err := tr.See(1); err != nil {
		t.Fatal(err)
	}
	if err := tr.See(2); err != nil {
		t.Fatal(err)
	}
	if err := tr.See(1); !errors.Is(err, ErrDuplicateID) {
		t.Fatalf("duplicate: %v", err)
	}
	if err := tr.See(3); err != nil {
		t.Fatal(err)
	}
}

func TestIDTrackerBudget(t *testing.T) {
	tr := NewIDTracker()
	for i := 0; i <= MaxTrackedIDs; i++ {
		if err := tr.See(uint64(i)); err != nil {
			t.Fatalf("see %d: %v", i, err)
		}
	}
	if err := tr.See(uint64(MaxTrackedIDs) + 1); !errors.Is(err, ErrTooManyIDs) {
		t.Fatalf("budget: %v", err)
	}
}

func TestM2AttachResumeFrom(t *testing.T) {
	// Absent cursor: ResumeFrom is nil (a fresh full attach).
	req, err := Parse([]byte(`{"id":1,"type":"session.attach","session_id":"` + testSessionID + `"}`))
	if err != nil {
		t.Fatal(err)
	}
	if req.ResumeFrom != nil {
		t.Fatalf("resume_from = %v, want nil", *req.ResumeFrom)
	}

	// Zero and the JS-safe maximum are both valid cursors.
	for _, v := range []uint64{0, MaxResumeFrom} {
		req, err := Parse([]byte(`{"id":1,"type":"session.attach","session_id":"` + testSessionID + `","resume_from":` + itoa(v) + `}`))
		if err != nil {
			t.Fatalf("resume_from %d: %v", v, err)
		}
		if req.ResumeFrom == nil || *req.ResumeFrom != v {
			t.Fatalf("resume_from = %v, want %d", req.ResumeFrom, v)
		}
	}

	// Above the JS-safe maximum is a protocol error, not a silent clamp.
	if _, err := Parse([]byte(`{"id":1,"type":"session.attach","session_id":"` + testSessionID + `","resume_from":9007199254740992}`)); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("oversized cursor: got %v, want ErrInvalidRequest", err)
	}
	// resume_from belongs to session.attach only.
	if _, err := Parse([]byte(`{"id":1,"type":"session.list","resume_from":4}`)); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("stray cursor: got %v, want ErrInvalidRequest", err)
	}
}

func itoa(v uint64) string {
	if v == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	return string(b[i:])
}

func TestM2AttachResponseContinuity(t *testing.T) {
	for _, c := range []string{ContinuityFull, ContinuityGapless, ContinuityGap} {
		r, err := ParseResponse(EncodeAttachResponse(5, 9, c, 1234))
		if err != nil {
			t.Fatalf("%s: %v", c, err)
		}
		if r.ChannelID == nil || *r.ChannelID != 9 || r.Continuity == nil || *r.Continuity != c || r.ReplayedFrom == nil || *r.ReplayedFrom != 1234 {
			t.Fatalf("%s: %+v", c, r)
		}
	}
}

func TestM2PresetList(t *testing.T) {
	req, err := Parse([]byte(`{"id":1,"type":"preset.list"}`))
	if err != nil || req.Type != TypePresetList {
		t.Fatalf("parse: %v %+v", err, req)
	}
	if _, err := Parse([]byte(`{"id":1,"type":"preset.list","cols":80}`)); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("stray field: %v", err)
	}

	presets := []Preset{
		{Name: "claude", Command: "claude", IconHint: "spark"},
		{Name: "codex", Command: "codex", IconHint: ""},
	}
	r, err := ParseResponse(EncodePresetListResponse(2, presets))
	if err != nil {
		t.Fatal(err)
	}
	if r.Type != TypePresetList || len(r.Presets) != 2 || r.Presets[0].Command != "claude" || r.Presets[1].IconHint != "" {
		t.Fatalf("response: %+v", r)
	}
	// Absent presets decode as an empty list, never null.
	r, err = ParseResponse(EncodePresetListResponse(3, nil))
	if err != nil || r.Presets == nil || len(r.Presets) != 0 {
		t.Fatalf("empty: %+v %v", r, err)
	}
}

func TestM2SessionEvent(t *testing.T) {
	bell := EncodeSessionEvent(SessionEvent{
		SessionID: testSessionID,
		Seq:       7,
		Kind:      EventBell,
		Text:      "build failed",
		Ts:        1750000000,
	})
	n, err := ParseNotification(bell)
	if err != nil {
		t.Fatal(err)
	}
	if n.Type != TypeSessionEvent || n.SessionID == nil || *n.SessionID != testSessionID ||
		n.Seq == nil || *n.Seq != 7 || n.Kind == nil || *n.Kind != EventBell ||
		n.Pattern != nil || n.Text == nil || *n.Text != "build failed" || n.Ts == nil || *n.Ts != 1750000000 {
		t.Fatalf("bell: %+v", n)
	}

	pat := EncodeSessionEvent(SessionEvent{
		SessionID: testSessionID,
		Seq:       8,
		Kind:      EventPattern,
		Pattern:   "error",
		Text:      "error: cannot find symbol",
		Ts:        1750000001,
	})
	n, err = ParseNotification(pat)
	if err != nil {
		t.Fatal(err)
	}
	if n.Kind == nil || *n.Kind != EventPattern || n.Pattern == nil || *n.Pattern != "error" {
		t.Fatalf("pattern: %+v", n)
	}

	// The app side must reject malformed event notifications.
	bad := []string{
		`{"type":"session.event","session_id":"` + strings.Repeat("a", 63) + `","seq":1,"kind":"bell","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":0,"kind":"bell","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":9007199254740992,"kind":"bell","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"beep","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"bell","pattern":"x","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"pattern","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"pattern","pattern":"` + strings.Repeat("p", MaxPatternNameLen+1) + `","ts":1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"bell","ts":-1}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"bell","ts":1,"text":"` + strings.Repeat("t", MaxPreviewLen+1) + `"}`,
		`{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"bell"}`,
	}
	for _, j := range bad {
		if _, err := ParseNotification([]byte(j)); !errors.Is(err, ErrCtrlFrame) {
			t.Fatalf("%s: got %v, want ErrCtrlFrame", j, err)
		}
	}
	// Max-length pattern name and text are accepted.
	ok := `{"type":"session.event","session_id":"` + testSessionID + `","seq":1,"kind":"pattern","pattern":"` + strings.Repeat("p", MaxPatternNameLen) + `","text":"` + strings.Repeat("t", MaxPreviewLen) + `","ts":1}`
	if _, err := ParseNotification([]byte(ok)); err != nil {
		t.Fatalf("max bounds: %v", err)
	}
}

func TestM2MetaPreview(t *testing.T) {
	meta := Meta{
		ID:      testSessionID,
		Kind:    KindShell,
		Cols:    80,
		Rows:    24,
		Running: true,
		Preview: "last line of output",
	}
	r, err := ParseResponse(EncodeListResponse(1, []Meta{meta}))
	if err != nil {
		t.Fatal(err)
	}
	if r.Sessions[0].Preview != meta.Preview {
		t.Fatalf("preview: %+v", r.Sessions[0])
	}
}
