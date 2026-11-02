package app.phonebridge

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * UI language: English on first launch whatever the system locale, Persian when the owner picks it (onboarding or
 * Settings). API 33+ keeps it as the system per-app language (also editable in system settings via the generated
 * localeConfig); older releases wrap the activity context in [wrap].
 */
object Lang {
    const val EN = "en"
    const val FA = "fa"

    /** Only "fa" is Persian. "" (v3.0.0's "follow the system"), null and any other tag fall back to English. */
    fun normalize(tag: String?): String = if (tag?.substringBefore('-')?.substringBefore('_') == FA) FA else EN

    /** [base] with its locale (and so its strings and layout direction) set to [tag]. */
    fun wrap(base: Context, tag: String): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(normalize(tag))) })

    /**
     * App start on API 33+: an empty per-app locale (fresh install, or an update from a build that followed the
     * system) gets the stored choice, English by default; one picked in system settings wins and is stored.
     */
    fun syncOnStart(ctx: Context, st: Store) {
        if (Build.VERSION.SDK_INT < 33) return
        val lm = ctx.getSystemService(LocaleManager::class.java) ?: return
        val current = lm.applicationLocales
        val tag = if (current.isEmpty) st.language else normalize(current[0].toLanguageTag())
        st.language = tag
        if (current.isEmpty || current[0].toLanguageTag() != tag) lm.applicationLocales = LocaleList.forLanguageTags(tag)
    }

    /** Applies [tag] without a restart: 33+ through LocaleManager (which recreates activities), older by recreate. */
    fun apply(ctx: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(normalize(tag))
        else (ctx as? Activity)?.recreate()
    }
}
