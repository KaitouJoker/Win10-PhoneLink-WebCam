package com.win10phonelink.webcam

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleHelper {
    const val PREFS_NAME = "win10_phonecam_prefs"
    const val KEY_LANGUAGE = "app_language"
    const val LANG_KO = "ko"
    const val LANG_EN = "en"

    private fun prefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext ?: context
        return appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun normalizeLanguage(code: String?): String {
        return if (code != null && code.trim().startsWith("ko", ignoreCase = true)) LANG_KO else LANG_EN
    }

    fun detectSystemLanguage(): String {
        val sysLocale = Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
        return normalizeLanguage(sysLocale.language)
    }

    fun getLanguage(context: Context): String {
        val saved = prefs(context).getString(KEY_LANGUAGE, null)
        return if (saved.isNullOrBlank()) detectSystemLanguage() else normalizeLanguage(saved)
    }

    fun setLanguage(context: Context, langCode: String): String {
        val normalized = normalizeLanguage(langCode)
        prefs(context)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .commit()
        return normalized
    }

    fun isKorean(context: Context): Boolean = getLanguage(context) == LANG_KO

    @Suppress("DEPRECATION")
    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        context.applicationContext?.resources?.let { appRes ->
            val appConfig = Configuration(appRes.configuration)
            appConfig.setLocale(locale)
            appConfig.setLayoutDirection(locale)
            appRes.updateConfiguration(appConfig, appRes.displayMetrics)
        }

        return context.createConfigurationContext(config)
    }

    fun wrapContext(base: Context): Context = applyLocale(base)
}
