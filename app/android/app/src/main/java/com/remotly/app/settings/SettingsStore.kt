package com.remotly.app.settings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

// Global app settings, schema version 4:
//
//   {
//     "v": 4,
//     "themeMode": "system" | "light" | "dark",
//     "dynamicColor": <bool>,
//     "terminalFontSize": <int, 8..32>,
//     "openKeyboardOnTerminal": <bool>,
//     "showExtraKeyRow": <bool>,
//     "cursorStyle": "block" | "bar" | "underline",
//     "hapticFeedback": <bool>,
//     "keyRepeatDelayMs": <int, 150..1000>,
//     "downloadFolderUri": <string>,
//     "filesShowHidden": <bool>,
//     "filesSortKey": "name" | "size" | "mtime" | "kind",
//     "filesSortDirection": "asc" | "desc"
//   }
//
// A file at an older version is missing fields this version added (or, for
// version 1 and 2, still carries notifyEnabled from the deleted terminal
// event notification feature). It is migrated forward by defaulting
// anything missing rather than being quarantined, because quarantining
// would silently drop the user's other preferences.
data class AppSettings(
    val themeMode: String = THEME_SYSTEM,
    val dynamicColor: Boolean = true,
    val terminalFontSize: Int = DEFAULT_FONT_SIZE,
    val openKeyboardOnTerminal: Boolean = true,
    val showExtraKeyRow: Boolean = true,
    val cursorStyle: String = CURSOR_BLOCK,
    val hapticFeedback: Boolean = true,
    val keyRepeatDelayMs: Int = DEFAULT_KEY_REPEAT_DELAY,
    /** Tree URI the file browser saves downloads into, or empty when unset. */
    val downloadFolderUri: String = "",
    val filesShowHidden: Boolean = false,
    val filesSortKey: String = SORT_NAME,
    val filesSortDirection: String = SORT_ASC,
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val CURSOR_BLOCK = "block"
        const val CURSOR_BAR = "bar"
        const val CURSOR_UNDERLINE = "underline"

        const val MIN_KEY_REPEAT_DELAY = 150
        const val MAX_KEY_REPEAT_DELAY = 1000
        const val DEFAULT_KEY_REPEAT_DELAY = 400

        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32
        const val DEFAULT_FONT_SIZE = 14

        const val SORT_NAME = "name"
        const val SORT_SIZE = "size"
        const val SORT_MTIME = "mtime"
        const val SORT_KIND = "kind"
        const val SORT_ASC = "asc"
        const val SORT_DESC = "desc"

        val THEME_MODES = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
        val CURSOR_STYLES = setOf(CURSOR_BLOCK, CURSOR_BAR, CURSOR_UNDERLINE)
        val SORT_KEYS = setOf(SORT_NAME, SORT_SIZE, SORT_MTIME, SORT_KIND)
        val SORT_DIRECTIONS = setOf(SORT_ASC, SORT_DESC)
    }
}

class SettingsStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SettingsStore(private val file: File) {

    @Synchronized
    fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return quarantine("unreadable settings file")
        }
        return try {
            parse(text)
        } catch (e: SettingsStoreException) {
            quarantine("corrupt settings file: ${e.message}")
        }
    }

    @Synchronized
    fun save(settings: AppSettings) {
        val normalized = normalize(settings)
        val obj = JsonObject().apply {
            addProperty("v", VERSION)
            addProperty("themeMode", normalized.themeMode)
            addProperty("dynamicColor", normalized.dynamicColor)
            addProperty("terminalFontSize", normalized.terminalFontSize)
            addProperty("openKeyboardOnTerminal", normalized.openKeyboardOnTerminal)
            addProperty("showExtraKeyRow", normalized.showExtraKeyRow)
            addProperty("hapticFeedback", normalized.hapticFeedback)
            addProperty("keyRepeatDelayMs", normalized.keyRepeatDelayMs)
            addProperty("cursorStyle", normalized.cursorStyle)
            addProperty("downloadFolderUri", normalized.downloadFolderUri)
            addProperty("filesShowHidden", normalized.filesShowHidden)
            addProperty("filesSortKey", normalized.filesSortKey)
            addProperty("filesSortDirection", normalized.filesSortDirection)
        }
        writeAtomic(GSON.toJson(obj).toByteArray())
    }

    // Clamps and constrains anything out of range. A settings file is a trust
    // boundary even when this process wrote it, because it survives upgrades.
    private fun normalize(s: AppSettings): AppSettings =
        s.copy(
            themeMode =
                if (s.themeMode in AppSettings.THEME_MODES) s.themeMode
                else AppSettings.THEME_SYSTEM,
            terminalFontSize =
                s.terminalFontSize.coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE),
            cursorStyle =
                if (s.cursorStyle in AppSettings.CURSOR_STYLES) s.cursorStyle
                else AppSettings.CURSOR_BLOCK,
            keyRepeatDelayMs = s.keyRepeatDelayMs.coerceIn(
                AppSettings.MIN_KEY_REPEAT_DELAY,
                AppSettings.MAX_KEY_REPEAT_DELAY,
            ),
            filesSortKey =
                if (s.filesSortKey in AppSettings.SORT_KEYS) s.filesSortKey
                else AppSettings.SORT_NAME,
            filesSortDirection =
                if (s.filesSortDirection in AppSettings.SORT_DIRECTIONS) s.filesSortDirection
                else AppSettings.SORT_ASC,
        )

    private fun parse(text: String): AppSettings {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw SettingsStoreException("settings file is not valid JSON")
        }
        val o =
            if (root.isJsonObject) root.asJsonObject
            else throw SettingsStoreException("settings file is not a JSON object")
        val version = o.get("v")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw SettingsStoreException("settings file has no version")
        if (version < VERSION_1 || version > VERSION) {
            throw SettingsStoreException("settings file has unsupported version")
        }
        // Versions 1 and 2 also carried notifyEnabled, for the terminal event
        // notification feature that no longer exists. It is simply dropped
        // rather than validated: a stale field in an old file is not this
        // version's problem.
        val defaults = AppSettings()
        return normalize(
            AppSettings(
                themeMode = string(o, "themeMode") ?: defaults.themeMode,
                dynamicColor = bool(o, "dynamicColor") ?: defaults.dynamicColor,
                terminalFontSize = int(o, "terminalFontSize") ?: defaults.terminalFontSize,
                openKeyboardOnTerminal =
                    bool(o, "openKeyboardOnTerminal") ?: defaults.openKeyboardOnTerminal,
                showExtraKeyRow = bool(o, "showExtraKeyRow") ?: defaults.showExtraKeyRow,
                hapticFeedback = bool(o, "hapticFeedback") ?: defaults.hapticFeedback,
                keyRepeatDelayMs = int(o, "keyRepeatDelayMs") ?: defaults.keyRepeatDelayMs,
                cursorStyle = string(o, "cursorStyle") ?: defaults.cursorStyle,
                downloadFolderUri = string(o, "downloadFolderUri") ?: defaults.downloadFolderUri,
                filesShowHidden = bool(o, "filesShowHidden") ?: defaults.filesShowHidden,
                filesSortKey = string(o, "filesSortKey") ?: defaults.filesSortKey,
                filesSortDirection =
                    string(o, "filesSortDirection") ?: defaults.filesSortDirection,
            ),
        )
    }

    private fun bool(o: JsonObject, key: String): Boolean? =
        runCatching { o.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean }.getOrNull()

    private fun int(o: JsonObject, key: String): Int? =
        runCatching { o.get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

    private fun string(o: JsonObject, key: String): String? =
        runCatching { o.get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()

    private fun quarantine(reason: String): AppSettings {
        val quarantined =
            File(
                file.parentFile,
                "${file.nameWithoutExtension}.corrupt-${System.currentTimeMillis()}${file.extension}",
            )
        if (!file.renameTo(quarantined)) {
            throw SettingsStoreException("cannot quarantine $reason")
        }
        return AppSettings()
    }

    private fun writeAtomic(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                throw SettingsStoreException("cannot replace settings file")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is SettingsStoreException) throw e
            throw SettingsStoreException("cannot write settings file", e)
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val VERSION = 4
        private const val VERSION_1 = 1

        private val GSON = Gson()
    }
}
