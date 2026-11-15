package com.win10phonelink.webcam

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleHelper {
    const val PREFS_NAME = "win10_phonecam_prefs"
    const val KEY_LANGUAGE = "app_language"
    const val LANG_KO = "ko"
    const val LANG_EN = "en"

    fun normalizeLanguage(code: String?): String {
        return if (code != null && code.trim().startsWith("ko", ignoreCase = true)) LANG_KO else LANG_EN
    }

    fun detectSystemLanguage(): String {
        val sysLocale = Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
        return normalizeLanguage(sysLocale.language)
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, null)
        return if (saved.isNullOrBlank()) detectSystemLanguage() else normalizeLanguage(saved)
    }

    fun setLanguage(context: Context, langCode: String): String {
        val normalized = normalizeLanguage(langCode)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()
        return normalized
    }

    fun isKorean(context: Context): Boolean = getLanguage(context) == LANG_KO

    fun wrapContext(base: Context): Context {
        val lang = getLanguage(base)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
