// Package protocol implements the Remotly version 1 protocol: bounded frame
// codecs, connection-local channel multiplexing, and the control message
// schema. It is transport and crypto independent: a Cipher implementation
// seals and opens frames, and a WebSocket or other byte pipe carries them.
package protocol

import "errors"

// Version is the only protocol version this build accepts.
const Version = 1

// Prologue is bound into the Noise transcript so a version change breaks the
// handshake before any application data is accepted.
const Prologue = "remotly-v1"

// Handshake mode bytes carried in the first client message.
const (
	ModeIK   byte = 0 // reconnect of a paired device
	ModePair byte = 1 // first-time pairing, XXpsk0
)

// Channel types. Types beyond channelCount are rejected before dispatch.
const (
	ChannelCtrl  byte = 0
	ChannelTerm  byte = 1
	ChannelFile  byte = 2 // reserved for M4; rejected in M1
	channelCount      = 3
)

// CtrlChannelID is the fixed id of the control channel.
const CtrlChannelID uint32 = 0

// Control message types.
const (
	TypeHello                 = "hello"
	TypeSessionCreate         = "session.create"
	TypeSessionList           = "session.list"
	TypeSessionAttach         = "session.attach"
	TypeSessionDetach         = "session.detach"
	TypeSessionResize         = "session.resize"
	TypeSessionKill           = "session.kill"
	TypeSessionRename         = "session.rename"
	TypePresetList            = "preset.list"
	TypeFSList                = "fs.list"
	TypeFSStat                = "fs.stat"
	TypeFSMkdir               = "fs.mkdir"
	TypeFSRemove              = "fs.remove"
	TypeFSRename              = "fs.rename"
	TypeFSRoots               = "fs.roots"
	TypeTransferCreate        = "transfer.create"
	TypeTransferResume        = "transfer.resume"
	TypeTransferComplete      = "transfer.complete"
	TypeTransferCancel        = "transfer.cancel"
	TypeTransferStatus        = "transfer.status"
	TypeTransferAck           = "transfer.ack"
	TypeTransferFailed        = "transfer.failed"
	TypeTransferDone          = "transfer.done"
	TypeChannelClose          = "channel.close"
	TypeChannelReplayComplete = "channel.replay_complete"
	TypeSessionUpdate         = "session.update"
	TypeSessionEvent          = "session.event"
)

// Session kinds.
const (
	KindShell = "shell"
	KindAgent = "agent"
)

// Replay continuity values carried in the session.attach response.
// "full": no cursor; the whole retained scrollback was replayed.
// "gapless": the cursor was inside the retained window; replay started at
// the cursor, so a live terminal that already rendered up to the cursor
// receives every subsequent byte exactly once.
// "gap": the cursor is older than the retained window; replay started at
// the window's oldest byte and bytes between the cursor and that point are
// lost. The app must treat the stream as truncated, never as gap-free.
const (
	ContinuityFull    = "full"
	ContinuityGapless = "gapless"
	ContinuityGap     = "gap"
)

// Terminal event kinds carried in session.event notifications.
const (
	EventBell    = "bell"
	EventPattern = "pattern"
)

// Request field bounds.
const (
	MaxTitleLen   = 200
	MaxCommandLen = 4096
	MinDimension  = 1
	MaxDimension  = 1000
	// MaxResumeFrom bounds a replay cursor. Cursors are byte offsets in the
	// session's cumulative output stream; the bound keeps them below 2^53 so
	// JavaScript numbers stay exact.
	MaxResumeFrom = 1<<53 - 1
	// MaxTrackedIDs bounds the request ids one connection may mint. Beyond
	// it the daemon can no longer guarantee uniqueness, so the connection
	// is dropped as a resource limit instead of tracking forever.
	MaxTrackedIDs = 1 << 20
)

// Frame and field bounds. Every one of these is checked before allocation.
const (
	// MaxHandshake is the Noise spec maximum message size.
	MaxHandshake = 65535
	// MaxTokenIDLen bounds the pairing token id carried in the handshake.
	MaxTokenIDLen = 64
	// MaxPayloadLen is the largest frame payload.
	MaxPayloadLen = 1 << 20 // 1 MiB
	// MaxControlLen is the largest control JSON frame.
	MaxControlLen = 64 << 10
	// MaxErrorLen bounds error message text.
	MaxErrorLen = 512
	// MaxDeviceNameLen bounds a pairing device name.
	MaxDeviceNameLen = 100
	// MaxMetaIDLen is a session id: 64 hex characters.
	MaxMetaIDLen = 64
	// MaxPreviewLen bounds a session preview line and event text: plain
	// text, control characters and escape sequences already stripped.
	MaxPreviewLen = 120
	// MaxPresetCount, MaxPresetNameLen, and MaxPresetIconLen bound the
	// daemon's configured session presets.
	MaxPresetCount   = 16
	MaxPresetNameLen = 50
	MaxPresetIconLen = 32
	// MaxPatternCount, MaxPatternNameLen, and MaxPatternLen bound the
	// daemon's configured output-pattern event rules.
	MaxPatternCount   = 32
	MaxPatternNameLen = 50
	MaxPatternLen     = 256
	// MaxEventSeq bounds the per-session event counter carried in
	// session.event, kept below 2^53 for JavaScript.
	MaxEventSeq = 1<<53 - 1

	// MaxID is the largest request id, kept below 2^53 so JavaScript
	// numbers stay exact.
	MaxID = 1<<53 - 1
	// MaxFSPage bounds a single fs.list page; the daemon caps a larger request.
	MaxFSPage = 500
	// MaxFSPathLen bounds a filesystem path in a control request.
	MaxFSPathLen = 4096
	// MaxTransferChunk bounds one file-channel chunk payload (bytes, not
	// counting the 8-byte offset prefix).
	MaxTransferChunk = 1 << 20
)

// Connection and channel bounds.
const (
	MaxConnections    = 16
	MaxChannels       = 64
	ChannelQueueCap   = 256 // frames per channel send queue
	ChannelQueueBytes = 8 << 20
)

// Close codes for the WebSocket layer.
const (
	CloseVersion  = 4000
	CloseAuth     = 4001
	CloseProtocol = 4002
	CloseToken    = 4003
	CloseLimit    = 4004
)

// Error codes carried in control error responses. They are stable strings;
// both sides match on them.
const (
	CodeInvalidRequest   = "invalid_request"
	CodeUnknownType      = "unknown_type"
	CodeUnknownSession   = "unknown_session"
	CodeCapacity         = "capacity"
	CodeSessionExited    = "session_exited"
	CodeAttachmentLimit  = "attachment_limit"
	CodeUnknownChannel   = "unknown_channel"
	CodeHelloRequired    = "hello_required"
	CodeDuplicateID      = "duplicate_request_id"
	CodeSpawnFailed      = "spawn_failed"
	CodeCursorOutOfRange = "cursor_out_of_range"
	CodeDeviceUnknown    = "device_unknown"
	CodeDeviceRevoked    = "device_revoked"
	CodeTokenUnknown     = "token_unknown"
	CodeTokenExpired     = "token_expired"
	CodeTokenUsed        = "token_used"
	CodeNameInvalid      = "name_invalid"
	CodeKeyInvalid       = "key_invalid"
	CodeControlTooLarge  = "control_frame_too_large"
	CodeBadJSON          = "bad_json"
	CodeNotAuthenticated = "not_authenticated"
	// Filesystem metadata error codes (fs.* operations).
	CodeFSNotFound    = "fs_not_found"
	CodeFSNotDir      = "fs_not_dir"
	CodeFSIsDir       = "fs_is_dir"
	CodeFSNotEmpty    = "fs_not_empty"
	CodeFSPermission  = "fs_permission"
	CodeFSExist       = "fs_exist"
	CodeFSInvalidPath = "fs_invalid_path"
	// Transfer error codes (transfer.* operations).
	CodeTransferNotFound      = "transfer_not_found"
	CodeTransferNotAuthorized = "transfer_not_authorized"
	CodeTransferCapacity      = "transfer_capacity"
	CodeTransferTooLarge      = "transfer_too_large"
	CodeTransferBadOffset     = "transfer_bad_offset"
	CodeTransferOverLength    = "transfer_over_length"
	CodeTransferHashMismatch  = "transfer_hash_mismatch"
	CodeTransferSourceChanged = "transfer_source_changed"
	CodeTransferIncomplete    = "transfer_incomplete"
	CodeTransferConflict      = "transfer_conflict"
	CodeTransferInvalidArg    = "transfer_invalid_arg"
)

// Channel close reasons.
const (
	ReasonSessionExited = "session_exited"
	ReasonOverflow      = "overflow"
	ReasonDetached      = "detached"
	ReasonClosed        = "closed"
)

// Errors from the frame and mux layer. Callers match with errors.Is.
var (
	ErrVarintTooLong   = errors.New("protocol: varint too long")
	ErrVarintTruncated = errors.New("protocol: varint truncated")
	ErrBadChannel      = errors.New("protocol: unsupported channel type")
	ErrFrameTooLarge   = errors.New("protocol: frame exceeds size limit")
	ErrFrameTooSmall   = errors.New("protocol: frame smaller than tag")
	ErrFrameTruncated  = errors.New("protocol: frame truncated")
	ErrDecrypt         = errors.New("protocol: authentication failed")
	ErrNonceExhausted  = errors.New("protocol: nonce exhausted")
	ErrChannelFull     = errors.New("protocol: channel send queue full")
	ErrChannelClosed   = errors.New("protocol: channel closed")
	ErrUnknownChannel  = errors.New("protocol: unknown channel")
	ErrTooManyChannels = errors.New("protocol: channel limit reached")
	ErrMuxClosed       = errors.New("protocol: mux closed")
	ErrCtrlFrame       = errors.New("protocol: control frame shape violation")
	ErrBadJSON         = errors.New("protocol: control JSON malformed")
	ErrDuplicateID     = errors.New("protocol: duplicate request id")
	ErrTooManyIDs      = errors.New("protocol: request id limit reached")
	ErrUnknownType     = errors.New("protocol: unknown message type")
	ErrInvalidRequest  = errors.New("protocol: invalid request")
)
