package com.remotly.app.core

enum class ErrorKind { Network, Terminal, Storage, Unknown }

/**
 * A failure on its way to the screen.
 *
 * [message] is always safe to render. [cause] is kept for logging and is
 * never shown: a raw failure from the SSH or storage layer can carry a path,
 * a host name, or the text of a server reply.
 */
data class RemotlyError(
    val kind: ErrorKind,
    val message: String,
    val code: String? = null,
    val cause: Throwable? = null,
)

/**
 * Short, specific copy per kind, so a failed screen says what happened and,
 * where it can, what to do next.
 */
private val MESSAGES = mapOf(
    ErrorKind.Network to "Cannot reach the host. Check the network and try again.",
    ErrorKind.Terminal to "The terminal session stopped. Reopen the session to continue.",
    ErrorKind.Storage to "Saved hosts could not be read. Your data has not been changed.",
    ErrorKind.Unknown to "Something went wrong. Try again.",
)

fun messageFor(kind: ErrorKind): String = MESSAGES.getValue(kind)

/**
 * Normalizes a thrown value into something a screen can show.
 *
 * Only a [RemotlyException], which this app raised deliberately, carries its
 * own user-facing text. Anything else is an internal failure whose message is
 * not written for a user and may name a path or a host, so the standard copy
 * for the kind is used and the original is kept as the cause.
 */
fun toRemotlyError(cause: Throwable?, fallbackKind: ErrorKind = ErrorKind.Unknown): RemotlyError {
    if (cause is RemotlyException) return cause.error
    return RemotlyError(fallbackKind, messageFor(fallbackKind), cause = cause)
}

/** Thrown where the app has better copy than the standard message for the kind. */
class RemotlyException(val error: RemotlyError) : Exception(error.message, error.cause) {
    constructor(
        kind: ErrorKind,
        message: String = messageFor(kind),
        code: String? = null,
        cause: Throwable? = null,
    ) : this(RemotlyError(kind, message, code, cause))
}
