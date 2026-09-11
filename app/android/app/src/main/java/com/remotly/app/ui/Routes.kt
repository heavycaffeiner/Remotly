package com.remotly.app.ui

/**
 * Every destination, and the argument names its route template uses.
 *
 * Arguments stay small and printable. A credential, a private key, or a whole
 * record never travels through navigation: the screen looks it up from the
 * store by id.
 */
object Routes {
    const val HOSTS = "hosts"
    const val SETTINGS = "settings"

    const val ARG_HOST_ID = "hostId"
    const val ARG_HOST_NAME = "hostName"
    const val ARG_WORKSPACE_ID = "workspaceId"
    const val ARG_LABEL = "label"
    const val ARG_SESSION = "session"

    const val HOST_EDITOR = "hostEditor?$ARG_HOST_ID={$ARG_HOST_ID}"
    const val SSH_TERMINAL = "sshTerminal/{$ARG_HOST_ID}"
    const val FILES = "files/{$ARG_HOST_ID}"
    const val HERDR_WORKSPACE =
        "herdr/{$ARG_HOST_ID}?$ARG_HOST_NAME={$ARG_HOST_NAME}" +
            "&$ARG_WORKSPACE_ID={$ARG_WORKSPACE_ID}" +
            "&$ARG_LABEL={$ARG_LABEL}" +
            "&$ARG_SESSION={$ARG_SESSION}"

    /** Opens the editor on an existing host, or on a blank form when null. */
    fun hostEditor(hostId: String? = null): String =
        if (hostId == null) "hostEditor" else "hostEditor?$ARG_HOST_ID=$hostId"

    fun sshTerminal(hostId: String): String = "sshTerminal/$hostId"

    fun files(hostId: String): String = "files/$hostId"

    fun herdrWorkspace(
        hostId: String,
        hostName: String,
        workspaceId: String? = null,
        label: String? = null,
        session: String? = null,
    ): String = buildString {
        append("herdr/").append(hostId)
        append("?").append(ARG_HOST_NAME).append("=").append(hostName)
        if (workspaceId != null) append("&").append(ARG_WORKSPACE_ID).append("=").append(workspaceId)
        if (label != null) append("&").append(ARG_LABEL).append("=").append(label)
        if (session != null) append("&").append(ARG_SESSION).append("=").append(session)
    }
}
