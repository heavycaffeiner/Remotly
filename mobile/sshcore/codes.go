package sshcore

// SshCode strings. Mirrors SshTypes.kt verbatim so the JS error mapping
// (RemotlyError) is untouched.
const (
	CodeConnectFailed   = "ssh_connect_failed"
	CodeAuthFailed      = "ssh_auth_failed"
	CodeHostKeyRejected = "ssh_host_key_rejected"
	CodeHostKeyChanged  = "ssh_host_key_changed"
	CodePtyFailed       = "ssh_pty_failed"
	CodeRemoteClosed    = "ssh_remote_closed"
	CodeCancelled       = "ssh_cancelled"
	CodeNetwork         = "ssh_network"
	CodeTimeout         = "ssh_timeout"
	CodeProtocol        = "ssh_protocol"
)

// Stage names the operation that failed. The public SshCode stays as it was so
// existing error mapping keeps working; the stage is what makes a Windows
// interoperability failure diagnosable, because "ssh_connect_failed" alone
// cannot distinguish a refused TCP connection from a rejected key exchange.
const (
	StageDial        = "ssh_dial_failed"
	StageHandshake   = "ssh_handshake_failed"
	StageAuth        = "ssh_auth_failed"
	StageHostKey     = "ssh_host_key_rejected"
	StageChannel     = "ssh_channel_failed"
	StagePty         = "ssh_pty_failed"
	StageShell       = "ssh_shell_failed"
	StageRemoteClose = "ssh_remote_closed"
	StageTimeout     = "ssh_timeout"
	StageCancelled   = "ssh_cancelled"
)

// Close codes for the SSH terminal. Mirrors SshTypes.kt CloseCode.
const (
	CloseNormal    = 1000
	CloseGoingAway = 1001
)
