package protocol

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"sync"
	"unicode/utf8"
)

// Request is a validated control request from the app. All fields are
// pointers: presence is part of the validation, and only the fields of the
// request's type are set.
type Request struct {
	ID   uint64
	Type string

	// hello
	DeviceName *string
	DevicePub  [32]byte
	// session.create
	Kind    *string
	Title   *string
	Command *string
	Cwd     *string
	Cols    *int
	Rows    *int
	// session.attach, session.resize, session.kill
	SessionID *string
	// session.attach
	ResumeFrom *uint64
	// session.detach
	ChannelID *uint32
	// fs.*
	Path       *string
	From       *string
	To         *string
	Offset     *int
	Limit      *int
	RemoveKind *string
	// transfer.*
	Direction    *string
	ExpectedSize *int64
	Hash         *string
	Conflict     *string
	TransferID   *string
}

// wireRequest mirrors the control envelope. Pointers distinguish absent from
// zero values; DisallowUnknownFields rejects unknown fields at decode time.
type wireRequest struct {
	ID         *uint64 `json:"id"`
	Type       *string `json:"type"`
	DeviceName *string `json:"device_name"`
	DevicePub  *string `json:"device_pub"`
	Kind       *string `json:"kind"`
	Title      *string `json:"title"`
	Command    *string `json:"command"`
	Cwd        *string `json:"cwd"`
	Cols       *int    `json:"cols"`
	Rows       *int    `json:"rows"`
	SessionID  *string `json:"session_id"`
	ChannelID  *uint32 `json:"channel_id"`
	ResumeFrom *uint64 `json:"resume_from"`
	// fs.*
	Path       *string `json:"path"`
	From       *string `json:"from"`
	To         *string `json:"to"`
	Offset     *int    `json:"offset"`
	Limit      *int    `json:"limit"`
	RemoveKind *string `json:"remove_kind"`
	// transfer.*
	Direction    *string `json:"direction"`
	ExpectedSize *int64  `json:"expected_size"`
	Hash         *string `json:"hash"`
	Conflict     *string `json:"conflict"`
	TransferID   *string `json:"transfer_id"`
}

// typeRules lists, per message type, the request fields it may carry and the
// ones it requires. The id and type envelope fields are always required and
// always allowed, so they are not listed.
var typeRules = map[string][]fieldRule{
	TypeHello: {
		{name: "device_name", required: true},
		{name: "device_pub", required: true},
	},
	TypeSessionCreate: {
		{name: "kind", required: true},
		{name: "title"},
		{name: "command"},
		{name: "cwd"},
		{name: "cols"},
		{name: "rows"},
	},
	TypeSessionList: {},
	TypeSessionAttach: {
		{name: "session_id", required: true},
		{name: "resume_from"},
	},
	TypePresetList: {},
	TypeFSList: {
		{name: "path", required: true},
		{name: "offset"},
		{name: "limit"},
	},
	TypeFSStat: {
		{name: "path", required: true},
	},
	TypeFSMkdir: {
		{name: "path", required: true},
	},
	TypeFSRemove: {
		{name: "path", required: true},
		{name: "remove_kind", required: true},
	},
	TypeFSRename: {
		{name: "from", required: true},
		{name: "to", required: true},
	},
	TypeFSRoots: {},
	TypeTransferCreate: {
		{name: "direction", required: true},
		{name: "path", required: true},
		{name: "expected_size"},
		{name: "hash"},
		{name: "conflict"},
	},
	TypeTransferComplete: {
		{name: "transfer_id", required: true},
	},
	TypeTransferCancel: {
		{name: "transfer_id", required: true},
	},
	TypeTransferStatus: {
		{name: "transfer_id", required: true},
	},
	TypeTransferResume: {
		{name: "transfer_id", required: true},
	},
	TypeSessionDetach: {
		{name: "channel_id", required: true},
	},
	TypeSessionResize: {
		{name: "session_id", required: true},
		{name: "cols", required: true},
		{name: "rows", required: true},
	},
	TypeSessionKill: {
		{name: "session_id", required: true},
	},
}

type fieldRule struct {
	name     string
	required bool
}

var sessionIDPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// ErrRequest is a control request that failed validation but is recoverable:
// the daemon answers it with an error response instead of closing the
// connection. Code is the error code carried in that response. ID and Type
// echo the offending request so the app can correlate the answer.
type ErrRequest struct {
	ID   uint64
	Type string
	Code string
}

func (e *ErrRequest) Error() string {
	if e.Type == "" {
		return "protocol: " + e.Code
	}
	return fmt.Sprintf("protocol: %s for %s request %d", e.Code, e.Type, e.ID)
}

// Is matches the protocol sentinel this request error represents, so
// errors.Is(err, ErrUnknownType) and friends keep working on the wrapped form.
func (e *ErrRequest) Is(target error) bool {
	switch {
	case e.Code == CodeUnknownType && target == ErrUnknownType:
		return true
	case e.Code == CodeInvalidRequest && target == ErrInvalidRequest:
		return true
	}
	return false
}

// Parse decodes and validates an app-to-daemon control frame. A request id is
// mandatory (the app never sends notifications). Unknown types and fields
// that do not belong to the message type or fail value checks are rejected
// with *ErrRequest, which the transport answers with an error response;
// unparseable JSON is ErrBadJSON and a malformed envelope is ErrCtrlFrame.
func Parse(data []byte) (*Request, error) {
	obj, err := decodeObject(data)
	if err != nil {
		return nil, ErrBadJSON
	}
	rawID, ok := obj["id"]
	if !ok {
		return nil, ErrCtrlFrame
	}
	rawType, ok := obj["type"]
	if !ok {
		return nil, ErrCtrlFrame
	}
	var id uint64
	if err := json.Unmarshal(rawID, &id); err != nil {
		return nil, ErrBadJSON
	}
	if id > MaxID {
		return nil, ErrCtrlFrame
	}
	var typ string
	if err := json.Unmarshal(rawType, &typ); err != nil {
		return nil, ErrBadJSON
	}
	rules, known := typeRules[typ]
	if !known {
		return nil, &ErrRequest{ID: id, Type: typ, Code: CodeUnknownType}
	}
	allowed := make(map[string]bool, len(rules)+2)
	allowed["id"] = true
	allowed["type"] = true
	for _, r := range rules {
		if r.required {
			if _, ok := obj[r.name]; !ok {
				return nil, &ErrRequest{ID: id, Type: typ, Code: CodeInvalidRequest}
			}
		}
		allowed[r.name] = true
	}
	for key := range obj {
		if !allowed[key] {
			return nil, &ErrRequest{ID: id, Type: typ, Code: CodeInvalidRequest}
		}
	}
	var w wireRequest
	if err := decodeOne(data, &w); err != nil {
		return nil, ErrBadJSON
	}
	if err := checkValues(id, typ, &w); err != nil {
		return nil, err
	}
	req := &Request{
		ID:           *w.ID,
		Type:         *w.Type,
		DeviceName:   w.DeviceName,
		Kind:         w.Kind,
		Title:        w.Title,
		Command:      w.Command,
		Cwd:          w.Cwd,
		Cols:         w.Cols,
		Rows:         w.Rows,
		SessionID:    w.SessionID,
		ChannelID:    w.ChannelID,
		ResumeFrom:   w.ResumeFrom,
		Path:         w.Path,
		From:         w.From,
		To:           w.To,
		Offset:       w.Offset,
		Limit:        w.Limit,
		RemoveKind:   w.RemoveKind,
		Direction:    w.Direction,
		ExpectedSize: w.ExpectedSize,
		Hash:         w.Hash,
		Conflict:     w.Conflict,
		TransferID:   w.TransferID,
	}
	if w.DevicePub != nil {
		pub, _ := base64.RawURLEncoding.DecodeString(*w.DevicePub)
		copy(req.DevicePub[:], pub)
	}
	return req, nil
}

// checkValues validates field contents after the shape check passed.
func checkValues(id uint64, typ string, w *wireRequest) error {
	bad := func() error {
		return &ErrRequest{ID: id, Type: typ, Code: CodeInvalidRequest}
	}
	switch *w.Type {
	case TypeHello:
		if err := checkDeviceName(*w.DeviceName); err != nil {
			return bad()
		}
		pub, err := base64.RawURLEncoding.DecodeString(*w.DevicePub)
		if err != nil || len(pub) != 32 {
			return bad()
		}
	case TypeSessionCreate:
		if *w.Kind != KindShell && *w.Kind != KindAgent {
			return bad()
		}
		if *w.Kind == KindShell && w.Command != nil {
			return bad()
		}
		if *w.Kind == KindAgent && w.Command == nil {
			return bad()
		}
		if w.Command != nil && len(*w.Command) > MaxCommandLen {
			return bad()
		}
		if w.Title != nil && len(*w.Title) > MaxTitleLen {
			return bad()
		}
		if w.Cwd != nil && !strings.HasPrefix(*w.Cwd, "/") {
			return bad()
		}
		if w.Cols != nil && !inDimension(*w.Cols) {
			return bad()
		}
		if w.Rows != nil && !inDimension(*w.Rows) {
			return bad()
		}
	case TypeSessionResize:
		if !inDimension(*w.Cols) || !inDimension(*w.Rows) {
			return bad()
		}
	case TypeSessionAttach:
		if !sessionIDPattern.MatchString(*w.SessionID) {
			return bad()
		}
		if w.ResumeFrom != nil && *w.ResumeFrom > MaxResumeFrom {
			return bad()
		}
	case TypeSessionKill:
		if !sessionIDPattern.MatchString(*w.SessionID) {
			return bad()
		}
	case TypeFSList:
		if err := checkPath(*w.Path); err != nil {
			return bad()
		}
		if w.Offset != nil && *w.Offset < 0 {
			return bad()
		}
		if w.Limit != nil && (*w.Limit < 1 || *w.Limit > MaxFSPage) {
			return bad()
		}
	case TypeFSStat, TypeFSMkdir:
		if err := checkPath(*w.Path); err != nil {
			return bad()
		}
	case TypeFSRemove:
		if err := checkPath(*w.Path); err != nil {
			return bad()
		}
		if *w.RemoveKind != "file" && *w.RemoveKind != "dir" {
			return bad()
		}
	case TypeFSRename:
		if err := checkPath(*w.From); err != nil {
			return bad()
		}
		if err := checkPath(*w.To); err != nil {
			return bad()
		}
	case TypeTransferCreate:
		if *w.Direction != "up" && *w.Direction != "down" {
			return bad()
		}
		if err := checkPath(*w.Path); err != nil {
			return bad()
		}
		if w.ExpectedSize != nil && *w.ExpectedSize < 0 {
			return bad()
		}
		if w.Conflict != nil && *w.Conflict != "fail" && *w.Conflict != "replace" {
			return bad()
		}
		if w.Hash != nil && *w.Hash != "" && !isHexHash(*w.Hash) {
			return bad()
		}
	case TypeTransferComplete, TypeTransferCancel, TypeTransferStatus, TypeTransferResume:
		if !isTransferID(*w.TransferID) {
			return bad()
		}
	}
	return nil
}

func checkDeviceName(name string) error {
	if name == "" || len(name) > MaxDeviceNameLen {
		return ErrInvalidRequest
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f {
			return ErrInvalidRequest
		}
	}
	return nil
}

func inDimension(v int) bool { return v >= MinDimension && v <= MaxDimension }

// checkPath bounds a filesystem path early (length and emptiness). The
// platform-aware validation (absolute, NUL bytes, normalization) is done in
// the fsops package; this only keeps the frame and allocation bounded.
func checkPath(p string) error {
	if p == "" || len(p) > MaxFSPathLen {
		return ErrInvalidRequest
	}
	return nil
}

// isHexHash reports whether s is a 64-character lowercase hex SHA-256 digest.
func isHexHash(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// isTransferID reports whether s is a well-formed transfer id: 32 lowercase
// hex characters (16 random bytes).
func isTransferID(s string) bool {
	if len(s) != 32 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// decodeObject decodes exactly one JSON object from data. Anything else,
// including trailing data, is an error.
func decodeObject(data []byte) (map[string]json.RawMessage, error) {
	var obj map[string]json.RawMessage
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(&obj); err != nil {
		return nil, err
	}
	// Token reports trailing data as (token, nil), so a nil error is not
	// proof that the input ended.
	if _, err := dec.Token(); err != io.EOF {
		if err == nil {
			err = errTrailingData
		}
		return nil, err
	}
	if obj == nil {
		return nil, errJSONObject
	}
	return obj, nil
}

var (
	errJSONObject   = errors.New("not a JSON object")
	errTrailingData = errors.New("trailing data after JSON value")
)

// decodeOne decodes exactly one JSON value into v, rejecting trailing data.
func decodeOne(data []byte, v interface{}) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(v); err != nil {
		return err
	}
	if _, err := dec.Token(); err != io.EOF {
		if err == nil {
			return errTrailingData
		}
		return err
	}
	return nil
}

// Entry is one filesystem object in an fs.list or fs.stat response. It is the
// wire form of a filesystem metadata record; Name is the platform-native
// basename kept byte-faithful, and Perm carries the full mode bits.
type Entry struct {
	Name       string `json:"name"`
	IsDir      bool   `json:"is_dir"`
	IsSymlink  bool   `json:"is_symlink"`
	Size       int64  `json:"size"`
	ModTime    int64  `json:"mod_time"`
	Perm       uint32 `json:"perm"`
	LinkTarget string `json:"link_target,omitempty"`
}

// Error is the error object of a control response.
type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Exit is the terminal state of a session inside a Meta.
type Exit struct {
	Code   int    `json:"code"`
	Signal string `json:"signal"`
}

// Meta is the session metadata object. Times are RFC 3339 strings.
// Preview is the last retained output line as plain text (escape sequences
// and control characters stripped, at most MaxPreviewLen bytes); it is
// empty when the session has no retained output.
type Meta struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	Kind         string `json:"kind"`
	Command      string `json:"command"`
	Cwd          string `json:"cwd"`
	Cols         int    `json:"cols"`
	Rows         int    `json:"rows"`
	CreatedAt    string `json:"created_at"`
	LastActivity string `json:"last_activity"`
	Running      bool   `json:"running"`
	Exit         *Exit  `json:"exit"`
	Preview      string `json:"preview,omitempty"`
}

// Preset is one configured agent session preset: the app renders presets
// as one-tap session creation actions. All fields are bounded plain text.
type Preset struct {
	Name     string `json:"name"`
	Command  string `json:"command"`
	IconHint string `json:"icon_hint"`
}

// Response is a decoded control response, for the client side. Unknown
// result fields are tolerated so a newer peer can add them.
type Response struct {
	ID           uint64   `json:"id"`
	Type         string   `json:"type"`
	Error        *Error   `json:"error"`
	DaemonName   string   `json:"daemon_name"`
	DaemonPub    string   `json:"daemon_pub"`
	Session      *Meta    `json:"session"`
	Sessions     []Meta   `json:"sessions"`
	ChannelID    *uint32  `json:"channel_id"`
	Continuity   *string  `json:"continuity"`
	ReplayedFrom *uint64  `json:"replayed_from"`
	Presets      []Preset `json:"presets"`
	// fs.*
	Entries []Entry  `json:"entries"`
	More    bool     `json:"more"`
	Total   int      `json:"total"`
	Entry   *Entry   `json:"entry"`
	Roots   []string `json:"roots"`
	// transfer.*
	TransferID   *string `json:"transfer_id"`
	TransferDir  *string `json:"direction"`
	TransferSize *int64  `json:"expected_size"`
	TransferHash *string `json:"hash"`
	Offset64     *int64  `json:"offset"`
	ResumeOff    *int64  `json:"resume_offset"`
}

// Notification is a decoded control notification, for the client side.
type Notification struct {
	Type      string  `json:"type"`
	ChannelID *uint32 `json:"channel_id"`
	Reason    *string `json:"reason"`
	Session   *Meta   `json:"session"`
	// channel.replay_complete
	Offset *uint64 `json:"offset"`
	// session.event
	SessionID *string `json:"session_id"`
	Seq       *uint64 `json:"seq"`
	Kind      *string `json:"kind"`
	Pattern   *string `json:"pattern"`
	Text      *string `json:"text"`
	Ts        *int64  `json:"ts"`
}

// SessionEvent is one terminal event notification: a bell or a configured
// output-pattern match on a session. Seq is a per-session monotonic counter
// the app uses for deduplication. Text is bounded plain text; it is
// terminal content and must never reach logs or analytics.
type SessionEvent struct {
	SessionID string
	Seq       uint64
	Kind      string
	Pattern   string
	Text      string
	Ts        int64
}

// ParseResponse decodes a daemon-to-app control response. Unknown result
// fields are tolerated so a newer peer can add them.
func ParseResponse(data []byte) (*Response, error) {
	obj, err := decodeObject(data)
	if err != nil {
		return nil, ErrBadJSON
	}
	if _, ok := obj["id"]; !ok {
		return nil, ErrCtrlFrame
	}
	var r Response
	if err := decodeOne(data, &r); err != nil {
		return nil, ErrBadJSON
	}
	if r.Type == "" {
		return nil, ErrCtrlFrame
	}
	return &r, nil
}

// ParseNotification decodes a daemon-to-app control notification and checks
// the required fields of each known type. Unknown types pass through so a
// newer peer can probe.
func ParseNotification(data []byte) (*Notification, error) {
	if _, err := decodeObject(data); err != nil {
		return nil, ErrBadJSON
	}
	var n Notification
	if err := decodeOne(data, &n); err != nil {
		return nil, ErrBadJSON
	}
	if n.Type == "" {
		return nil, ErrCtrlFrame
	}
	switch n.Type {
	case TypeChannelClose:
		if n.ChannelID == nil || n.Reason == nil {
			return nil, ErrCtrlFrame
		}
		switch *n.Reason {
		case ReasonSessionExited, ReasonOverflow, ReasonDetached, ReasonClosed:
		default:
			return nil, ErrCtrlFrame
		}
	case TypeSessionUpdate:
		if n.Session == nil {
			return nil, ErrCtrlFrame
		}
	case TypeChannelReplayComplete:
		if n.ChannelID == nil || n.Offset == nil {
			return nil, ErrCtrlFrame
		}
	case TypeSessionEvent:
		if n.Seq == nil || n.Kind == nil || n.SessionID == nil || n.Ts == nil {
			return nil, ErrCtrlFrame
		}
		// Seq starts at 1 per session; zero is not a valid value.
		if *n.Seq < 1 || *n.Seq > MaxEventSeq || *n.Ts < 0 {
			return nil, ErrCtrlFrame
		}
		switch *n.Kind {
		case EventBell:
			if n.Pattern != nil || !sessionIDPattern.MatchString(*n.SessionID) {
				return nil, ErrCtrlFrame
			}
		case EventPattern:
			if n.Pattern == nil || len(*n.Pattern) > MaxPatternNameLen ||
				!sessionIDPattern.MatchString(*n.SessionID) {
				return nil, ErrCtrlFrame
			}
		default:
			return nil, ErrCtrlFrame
		}
		if n.Text != nil && len(*n.Text) > MaxPreviewLen {
			return nil, ErrCtrlFrame
		}
	}
	return &n, nil
}

// clampError bounds an error message and keeps it on a UTF-8 boundary.
func clampError(s string) string {
	if len(s) <= MaxErrorLen {
		return s
	}
	t := s[:MaxErrorLen]
	for len(t) > 0 && !utf8.ValidString(t) {
		t = t[:len(t)-1]
	}
	return t
}

func encodeJSON(v interface{}) []byte {
	data, err := json.Marshal(v)
	if err != nil {
		// All response types hold JSON-safe values; a marshal failure is a
		// programming bug.
		panic(err)
	}
	return data
}

// EncodeHelloResponse renders the hello response.
func EncodeHelloResponse(id uint64, daemonName string, daemonPub [32]byte) []byte {
	return encodeJSON(struct {
		ID         uint64 `json:"id"`
		Type       string `json:"type"`
		DaemonName string `json:"daemon_name"`
		DaemonPub  string `json:"daemon_pub"`
	}{id, TypeHello, daemonName, base64.RawURLEncoding.EncodeToString(daemonPub[:])})
}

// EncodeErrorResponse renders an error response to a request.
func EncodeErrorResponse(id uint64, reqType, code, message string) []byte {
	return encodeJSON(struct {
		ID    uint64 `json:"id"`
		Type  string `json:"type"`
		Error Error  `json:"error"`
	}{id, reqType, Error{Code: code, Message: clampError(message)}})
}

// EncodeCreateResponse renders the session.create response.
func EncodeCreateResponse(id uint64, m Meta) []byte {
	return encodeJSON(struct {
		ID      uint64 `json:"id"`
		Type    string `json:"type"`
		Session Meta   `json:"session"`
	}{id, TypeSessionCreate, m})
}

// EncodeListResponse renders the session.list response.
func EncodeListResponse(id uint64, sessions []Meta) []byte {
	if sessions == nil {
		sessions = []Meta{}
	}
	return encodeJSON(struct {
		ID       uint64 `json:"id"`
		Type     string `json:"type"`
		Sessions []Meta `json:"sessions"`
	}{id, TypeSessionList, sessions})
}

// EncodeAttachResponse renders the session.attach response. Continuity is
// one of ContinuityFull, ContinuityGapless, ContinuityGap; ReplayedFrom is
// the output-stream byte offset the replay started at.
func EncodeAttachResponse(id uint64, channelID uint32, continuity string, replayedFrom uint64) []byte {
	switch continuity {
	case ContinuityFull, ContinuityGapless, ContinuityGap:
	default:
		panic("protocol: bad continuity " + continuity)
	}
	return encodeJSON(struct {
		ID           uint64 `json:"id"`
		Type         string `json:"type"`
		ChannelID    uint32 `json:"channel_id"`
		Continuity   string `json:"continuity"`
		ReplayedFrom uint64 `json:"replayed_from"`
	}{id, TypeSessionAttach, channelID, continuity, replayedFrom})
}

// EncodePresetListResponse renders the preset.list response.
func EncodePresetListResponse(id uint64, presets []Preset) []byte {
	if presets == nil {
		presets = []Preset{}
	}
	return encodeJSON(struct {
		ID      uint64   `json:"id"`
		Type    string   `json:"type"`
		Presets []Preset `json:"presets"`
	}{id, TypePresetList, presets})
}

// EncodeFSListResponse renders the fs.list response: a bounded, name-sorted
// page of entries plus the total count and a more flag.
func EncodeFSListResponse(id uint64, entries []Entry, more bool, total int) []byte {
	if entries == nil {
		entries = []Entry{}
	}
	return encodeJSON(struct {
		ID      uint64  `json:"id"`
		Type    string  `json:"type"`
		Entries []Entry `json:"entries"`
		More    bool    `json:"more"`
		Total   int     `json:"total"`
	}{id, TypeFSList, entries, more, total})
}

// EncodeFSStatResponse renders the fs.stat response.
func EncodeFSStatResponse(id uint64, e Entry) []byte {
	return encodeJSON(struct {
		ID    uint64 `json:"id"`
		Type  string `json:"type"`
		Entry Entry  `json:"entry"`
	}{id, TypeFSStat, e})
}

// EncodeFSRootsResponse renders the fs.roots response.
func EncodeFSRootsResponse(id uint64, roots []string) []byte {
	if roots == nil {
		roots = []string{}
	}
	return encodeJSON(struct {
		ID    uint64   `json:"id"`
		Type  string   `json:"type"`
		Roots []string `json:"roots"`
	}{id, TypeFSRoots, roots})
}

// EncodePlainResponse renders a response with no result fields:
// session.detach, session.resize, session.kill, and the fs mutations
// (fs.mkdir, fs.remove, fs.rename).
func EncodePlainResponse(id uint64, reqType string) []byte {
	return encodeJSON(struct {
		ID   uint64 `json:"id"`
		Type string `json:"type"`
	}{id, reqType})
}

// EncodeReplayComplete renders the channel.replay_complete notification.
// Offset is the cumulative output offset just past the last replayed byte:
// the value a client passes as resume_from to receive only the output it has
// not seen yet.
func EncodeReplayComplete(channelID uint32, offset uint64) []byte {
	return encodeJSON(struct {
		Type      string `json:"type"`
		ChannelID uint32 `json:"channel_id"`
		Offset    uint64 `json:"offset"`
	}{TypeChannelReplayComplete, channelID, offset})
}

// EncodeTransferCreateResponse renders the transfer.create response: the new
// transfer id, the file channel for its chunk data, the agreed size and hash,
// and the resume offset (zero for a fresh upload).
func EncodeTransferCreateResponse(id uint64, transferID string, channelID uint32, direction string, size int64, hash string, resumeOff int64) []byte {
	return encodeJSON(struct {
		ID           uint64 `json:"id"`
		Type         string `json:"type"`
		TransferID   string `json:"transfer_id"`
		ChannelID    uint32 `json:"channel_id"`
		Direction    string `json:"direction"`
		ExpectedSize int64  `json:"expected_size"`
		Hash         string `json:"hash"`
		ResumeOffset int64  `json:"resume_offset"`
	}{id, TypeTransferCreate, transferID, channelID, direction, size, hash, resumeOff})
}

// EncodeTransferStatusResponse renders the transfer.status response, used for
// resume queries: the current offset, the agreed size, and the whole-file hash.
func EncodeTransferStatusResponse(id uint64, transferID string, offset, size int64, direction, hash string) []byte {
	return encodeJSON(struct {
		ID           uint64 `json:"id"`
		Type         string `json:"type"`
		TransferID   string `json:"transfer_id"`
		Offset       int64  `json:"offset"`
		ExpectedSize int64  `json:"expected_size"`
		Direction    string `json:"direction"`
		Hash         string `json:"hash"`
	}{id, TypeTransferStatus, transferID, offset, size, direction, hash})
}

// EncodeTransferCompleteResponse renders the transfer.complete response with
// the verified whole-file hash.
func EncodeTransferCompleteResponse(id uint64, transferID, hash string) []byte {
	return encodeJSON(struct {
		ID         uint64 `json:"id"`
		Type       string `json:"type"`
		TransferID string `json:"transfer_id"`
		Hash       string `json:"hash"`
	}{id, TypeTransferComplete, transferID, hash})
}

// EncodeTransferAck renders the transfer.ack notification: the upload offset
// acknowledged durably after an applied chunk.
func EncodeTransferAck(transferID string, offset int64) []byte {
	return encodeJSON(struct {
		Type       string `json:"type"`
		TransferID string `json:"transfer_id"`
		Offset     int64  `json:"offset"`
	}{TypeTransferAck, transferID, offset})
}

// EncodeTransferFailed renders the transfer.failed notification with the
// protocol error code.
func EncodeTransferFailed(transferID, code string) []byte {
	return encodeJSON(struct {
		Type       string `json:"type"`
		TransferID string `json:"transfer_id"`
		Code       string `json:"code"`
	}{TypeTransferFailed, transferID, code})
}

// EncodeTransferDone renders the transfer.done notification, sent when a
// download has pushed its final byte onto the file channel.
func EncodeTransferDone(transferID string) []byte {
	return encodeJSON(struct {
		Type       string `json:"type"`
		TransferID string `json:"transfer_id"`
	}{TypeTransferDone, transferID})
}

// EncodeChannelClose renders the channel.close notification.
func EncodeChannelClose(channelID uint32, reason string) ([]byte, error) {
	switch reason {
	case ReasonSessionExited, ReasonOverflow, ReasonDetached, ReasonClosed:
	default:
		return nil, ErrCtrlFrame
	}
	return encodeJSON(struct {
		Type      string `json:"type"`
		ChannelID uint32 `json:"channel_id"`
		Reason    string `json:"reason"`
	}{TypeChannelClose, channelID, reason}), nil
}

// EncodeSessionUpdate renders the session.update notification.
func EncodeSessionUpdate(m Meta) []byte {
	return encodeJSON(struct {
		Type    string `json:"type"`
		Session Meta   `json:"session"`
	}{TypeSessionUpdate, m})
}

// EncodeSessionEvent renders the session.event notification.
func EncodeSessionEvent(ev SessionEvent) []byte {
	return encodeJSON(struct {
		Type      string `json:"type"`
		SessionID string `json:"session_id"`
		Seq       uint64 `json:"seq"`
		Kind      string `json:"kind"`
		Pattern   string `json:"pattern,omitempty"`
		Text      string `json:"text,omitempty"`
		Ts        int64  `json:"ts"`
	}{TypeSessionEvent, ev.SessionID, ev.Seq, ev.Kind, ev.Pattern, ev.Text, ev.Ts})
}

// IDTracker remembers the request ids one sender has used on a connection.
// Request ids are unique per connection per sender; a repeat is a protocol
// error, and the tracking budget itself is a resource limit.
type IDTracker struct {
	mu   sync.Mutex
	used map[uint64]struct{}
	full bool
}

// NewIDTracker builds an empty tracker.
func NewIDTracker() *IDTracker {
	return &IDTracker{used: make(map[uint64]struct{})}
}

// See records id. It returns ErrDuplicateID for a repeat and ErrTooManyIDs
// once the tracking budget is exhausted.
func (t *IDTracker) See(id uint64) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.full {
		return ErrTooManyIDs
	}
	if _, ok := t.used[id]; ok {
		return ErrDuplicateID
	}
	t.used[id] = struct{}{}
	if len(t.used) > MaxTrackedIDs {
		t.full = true
	}
	return nil
}
