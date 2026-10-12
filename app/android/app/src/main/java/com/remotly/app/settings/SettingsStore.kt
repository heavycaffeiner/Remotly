package com.remotly.app.settings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.File
import com.remotly.app.terminal.TerminalTheme
import com.remotly.app.terminal.TerminalThemePreference
import com.remotly.app.terminal.parseTerminalColor
import com.remotly.app.terminal.terminalColorHex

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
    val extraKeyVisibility: ExtraKeyVisibility = ExtraKeyVisibility.Automatic,
    val navigationGesturesEnabled: Boolean = false,
    val terminalTheme: TerminalThemePreference = TerminalThemePreference(),
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

enum class ExtraKeyVisibility { Automatic, Always, Hidden }

class SettingsStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SettingsStore(private val file: File) {

    private var recoveryRequired = false

    @Synchronized
    fun load(): AppSettings {
        if (!file.exists()) return AppSettings().also { recoveryRequired = false }
        return try {
            parse(readSettings()).also { recoveryRequired = false }
        } catch (e: Exception) {
            recoveryRequired = true
            if (e is SettingsStoreException) throw e
            throw SettingsStoreException("cannot read settings file", e)
        }
    }

    @Synchronized
    fun recoverWithDefaults(): AppSettings {
        if (file.exists()) {
            val backup = File.createTempFile("${file.name}.recovery-", ".json", file.absoluteFile.parentFile)
            try {
                file.inputStream().use { source ->
                    backup.outputStream().use { target -> source.copyTo(target); target.fd.sync() }
                }
            } catch (e: Exception) {
                throw SettingsStoreException("cannot back up settings file", e)
            }
        }
        recoveryRequired = false
        return try { writeSettings(AppSettings()); AppSettings() }
        catch (e: Exception) { recoveryRequired = true; throw e }
    }

    @Synchronized
    fun save(settings: AppSettings) {
        if (recoveryRequired) throw SettingsStoreException("settings file needs recovery")
        if (file.exists()) {
            try { parse(readSettings()) }
            catch (e: Exception) {
                recoveryRequired = true
                throw SettingsStoreException("settings file needs recovery", e)
            }
        }
        writeSettings(settings)
    }

    private fun writeSettings(settings: AppSettings) {
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
            addProperty("extraKeyVisibility", normalized.extraKeyVisibility.name.lowercase())
            addProperty("navigationGesturesEnabled", normalized.navigationGesturesEnabled)
            add("terminalTheme", JsonObject().apply {
                addProperty("id", normalized.terminalTheme.id)
                add("custom", themeJson(normalized.terminalTheme.custom))
            })
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
            terminalTheme = s.terminalTheme.normalized(),
        )

    private fun readSettings(): String {
        val bytes = file.inputStream().use { source ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                if (output.size() + count > MAX_SETTINGS_BYTES) throw SettingsStoreException("settings file is too large")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    private fun parse(text: String): AppSettings {
        val root = try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val value = GSON.getAdapter(JsonElement::class.java).read(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw SettingsStoreException("trailing settings data")
                value
            }
        } catch (e: Exception) {
            throw SettingsStoreException("settings file is not valid JSON")
        }
        val o =
            if (root.isJsonObject) root.asJsonObject
            else throw SettingsStoreException("settings file is not a JSON object")
        val version = int(o, "v") ?: throw SettingsStoreException("settings file has no version")
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
                extraKeyVisibility = string(o, "extraKeyVisibility")?.let { value ->
                    ExtraKeyVisibility.entries.firstOrNull { it.name.lowercase() == value }
                        ?: throw SettingsStoreException("invalid extra key visibility")
                } ?: bool(o, "showExtraKeyRow")?.let {
                    if (it) ExtraKeyVisibility.Always else ExtraKeyVisibility.Hidden
                } ?: ExtraKeyVisibility.Automatic,
                navigationGesturesEnabled = bool(o, "navigationGesturesEnabled") ?: (version < 5),
                terminalTheme = parseTheme(o),
            ),
        )
    }

    private fun bool(o: JsonObject, key: String): Boolean? {
        val v = o.get(key) ?: return null
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isBoolean) throw SettingsStoreException("invalid $key")
        return v.asBoolean
    }

    private fun int(o: JsonObject, key: String): Int? {
        val v = o.get(key) ?: return null
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber || !Regex("-?[0-9]+").matches(v.toString()))
            throw SettingsStoreException("invalid $key")
        return v.toString().toIntOrNull() ?: throw SettingsStoreException("invalid $key")
    }

    private fun string(o: JsonObject, key: String): String? {
        val v = o.get(key) ?: return null
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) throw SettingsStoreException("invalid $key")
        return v.asString
    }

    private fun themeJson(theme: TerminalTheme): JsonObject = JsonObject().apply {
        val colors = listOf(theme.background, theme.foreground, theme.cursor, theme.cursorText,
            theme.selectionBackground, theme.selectionForeground)
        COLOR_KEYS.zip(colors).forEach { (key, color) -> addProperty(key, terminalColorHex(color)) }
        add("ansi", com.google.gson.JsonArray().apply { theme.ansi.forEach { add(terminalColorHex(it)) } })
    }

    private fun parseTheme(o: JsonObject): TerminalThemePreference {
        val value = o.get("terminalTheme") ?: return TerminalThemePreference()
        if (!value.isJsonObject) throw SettingsStoreException("invalid terminal theme")
        val theme = value.asJsonObject
        val id = string(theme, "id") ?: "classic"
        val customValue = theme.get("custom") ?: run {
            if (id == "custom") throw SettingsStoreException("custom theme has no colors")
            return TerminalThemePreference(id).normalized()
        }
        if (!customValue.isJsonObject) throw SettingsStoreException("invalid custom theme")
        try {
            val custom = customValue.asJsonObject
            val colors = COLOR_KEYS.map { parseTerminalColor(string(custom, it) ?: error("missing $it")) }
            val ansi = custom.get("ansi")
            require(ansi != null && ansi.isJsonArray && ansi.asJsonArray.size() == 16) { "ANSI palette must contain sixteen colors" }
            val palette = ansi.asJsonArray.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "invalid ANSI color" }
                parseTerminalColor(it.asString)
            }
            return TerminalThemePreference(id, TerminalTheme(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], palette)).normalized()
        } catch (e: Exception) {
            throw SettingsStoreException("invalid custom theme: ${e.message}", e)
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        val parent = file.absoluteFile.parentFile ?: throw SettingsStoreException("settings directory is unavailable")
        if (!parent.isDirectory && !parent.mkdirs()) throw SettingsStoreException("cannot create settings directory")
        val tmp = try { File.createTempFile(file.name + ".", TMP_SUFFIX, parent) }
        catch (e: Exception) { throw SettingsStoreException("cannot create settings temporary file", e) }
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
        private const val MAX_SETTINGS_BYTES = 1024 * 1024
        private const val VERSION = 5
        private val COLOR_KEYS = listOf("background", "foreground", "cursor", "cursorText", "selectionBackground", "selectionForeground")
        private const val VERSION_1 = 1

        private val GSON = Gson()
    }
}
