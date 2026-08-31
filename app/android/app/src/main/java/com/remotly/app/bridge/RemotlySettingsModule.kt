package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsModule
import com.remotly.app.settings.SettingsStoreException
import com.remotly.app.specs.NativeRemotlySettingsSpec

// Error codes the settings bridge reports to JS.
internal object SettingsCodes {
    const val CONTEXT = -1
    const val STORE = -2
    const val INVALID = -3
}

// The global app settings bridge. A corrupt settings file is quarantined by
// the store and reported as the defaults; a version 1 file is migrated
// forward so an upgrade never loses the user's notification choice.
class RemotlySettingsModule(reactContext: ReactApplicationContext) :
    NativeRemotlySettingsSpec(reactContext) {

    override fun get(promise: Promise) {
        val store = SettingsModule.store
        if (store == null) {
            promise.reject(SettingsCodes.STORE.toString(), "settings store unavailable")
            return
        }
        val settings = store.load()
        SettingsModule.notifyEnabled = settings.notifyEnabled
        promise.resolve(settingsMap(settings))
    }

    private fun settingsMap(s: AppSettings) =
        Arguments.createMap().apply {
            putBoolean("notifyEnabled", s.notifyEnabled)
            putString("themeMode", s.themeMode)
            putBoolean("dynamicColor", s.dynamicColor)
            putInt("terminalFontSize", s.terminalFontSize)
            putBoolean("openKeyboardOnTerminal", s.openKeyboardOnTerminal)
            putBoolean("showExtraKeyRow", s.showExtraKeyRow)
            putBoolean("hapticFeedback", s.hapticFeedback)
            putDouble("keyRepeatDelayMs", s.keyRepeatDelayMs.toDouble())
            putString("cursorStyle", s.cursorStyle)
        }

    override fun reset(promise: Promise) {
        val store = SettingsModule.store
        if (store == null) {
            promise.reject(SettingsCodes.STORE.toString(), "settings store unavailable")
            return
        }
        // Only app preferences. Hosts, SSH credentials, accepted host keys,
        // and workspace state are deliberately untouched: a preference reset
        // must never be a data reset.
        val defaults = AppSettings()
        try {
            store.save(defaults)
            SettingsModule.notifyEnabled = defaults.notifyEnabled
            promise.resolve(settingsMap(defaults))
        } catch (e: SettingsStoreException) {
            promise.reject(SettingsCodes.STORE.toString(), e.message ?: "settings reset failed")
        }
    }

    override fun set(settings: ReadableMap, promise: Promise) {
        val store = SettingsModule.store
        if (store == null) {
            promise.reject(SettingsCodes.STORE.toString(), "settings store unavailable")
            return
        }
        val defaults = AppSettings()
        val next =
            AppSettings(
                notifyEnabled = bool(settings, "notifyEnabled", defaults.notifyEnabled),
                themeMode = string(settings, "themeMode", defaults.themeMode),
                dynamicColor = bool(settings, "dynamicColor", defaults.dynamicColor),
                terminalFontSize =
                    int(settings, "terminalFontSize", defaults.terminalFontSize),
                openKeyboardOnTerminal =
                    bool(settings, "openKeyboardOnTerminal", defaults.openKeyboardOnTerminal),
                showExtraKeyRow = bool(settings, "showExtraKeyRow", defaults.showExtraKeyRow),
                cursorStyle = string(settings, "cursorStyle", defaults.cursorStyle),
                hapticFeedback = bool(settings, "hapticFeedback", defaults.hapticFeedback),
                keyRepeatDelayMs =
                    int(settings, "keyRepeatDelayMs", defaults.keyRepeatDelayMs),
            )
        if (next.themeMode !in AppSettings.THEME_MODES) {
            promise.reject(SettingsCodes.INVALID.toString(), "themeMode")
            return
        }
        if (next.cursorStyle !in AppSettings.CURSOR_STYLES) {
            promise.reject(SettingsCodes.INVALID.toString(), "cursorStyle")
            return
        }
        if (next.keyRepeatDelayMs < AppSettings.MIN_KEY_REPEAT_DELAY ||
            next.keyRepeatDelayMs > AppSettings.MAX_KEY_REPEAT_DELAY
        ) {
            promise.reject(SettingsCodes.INVALID.toString(), "keyRepeatDelayMs")
            return
        }
        if (next.terminalFontSize < AppSettings.MIN_FONT_SIZE ||
            next.terminalFontSize > AppSettings.MAX_FONT_SIZE
        ) {
            promise.reject(SettingsCodes.INVALID.toString(), "terminalFontSize")
            return
        }
        try {
            store.save(next)
            SettingsModule.notifyEnabled = next.notifyEnabled
            promise.resolve(null)
        } catch (e: SettingsStoreException) {
            promise.reject(SettingsCodes.STORE.toString(), e.message ?: "settings save failed")
        }
    }

    private fun bool(m: ReadableMap, key: String, fallback: Boolean): Boolean =
        if (m.hasKey(key) && !m.isNull(key)) runCatching { m.getBoolean(key) }.getOrDefault(fallback)
        else fallback

    private fun int(m: ReadableMap, key: String, fallback: Int): Int =
        if (m.hasKey(key) && !m.isNull(key)) runCatching { m.getInt(key) }.getOrDefault(fallback)
        else fallback

    private fun string(m: ReadableMap, key: String, fallback: String): String =
        if (m.hasKey(key) && !m.isNull(key)) m.getString(key) ?: fallback else fallback
}
