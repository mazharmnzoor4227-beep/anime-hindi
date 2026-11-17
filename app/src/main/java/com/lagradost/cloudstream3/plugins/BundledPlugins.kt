package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.io.File

/**
 * Hindi Anime: anime extensions bundled inside the APK (assets/bundled_plugins).
 *
 * On every launch the bundled .cs3 files are copied into the app's plugin
 * directory (if missing or changed) and loaded with the same loader the app
 * uses for local plugins. No download, no repository setup, no user action.
 * Installing more extensions from inside the app (Settings -> Extensions ->
 * Add Repository) keeps working untouched: the bundled files are just local
 * plugins like any other.
 *
 * Verified bundled plugins (2026-11-18, live checks + code review):
 * AnimeWorldIndia (Hindi, v14), RareAnimesIndia (Hindi, v10, see caveat in
 * the release notes), NineAnime (en, v11), RaghavAnime (en, v98),
 * AniKotoAnime (en, v22), ReAnime (en, v10), Xanime (en, v6).
 * Dropped: AnimeInWeb (Indonesian-only, does not fit Hindi/English/Japanese).
 */
object BundledPlugins {
    private const val TAG = "BundledPlugins"
    private const val ASSET_DIR = "bundled_plugins"

    /** File name to expected size in bytes (from the verified raghav builds). */
    val BUNDLED_PLUGINS = listOf(
        "AnimeWorldIndia.cs3" to 70313L,
        "RareAnimesIndia.cs3" to 167153L,
        "NineAnime.cs3" to 56433L,
        "RaghavAnime.cs3" to 724600L,
        "AniKotoAnime.cs3" to 65043L,
        "ReAnime.cs3" to 61466L,
        "Xanime.cs3" to 57660L,
    )

    /** True while the bundled install is running; MainActivity's setup gate reads it. */
    @Volatile
    var installInProgress: Boolean = false

    private fun pluginDir(context: Context): File {
        return File(context.getExternalFilesDir(null), "plugins").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Copy any missing/changed bundled plugin into the plugin directory and
     * load every bundled plugin that is not already loaded. Safe to call on
     * every launch: already-loaded files are skipped.
     */
    suspend fun ensureBundledPlugins(context: Context) {
        installInProgress = true
        try {
            val dir = pluginDir(context)
            for ((fileName, expectedSize) in BUNDLED_PLUGINS) {
                try {
                    val dest = File(dir, fileName)
                    if (!dest.exists() || dest.length() != expectedSize) {
                        context.assets.open("$ASSET_DIR/$fileName").use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "Copied bundled plugin $fileName (${dest.length()} bytes)")
                    }
                    if (!PluginManager.plugins.containsKey(dest.absolutePath)) {
                        PluginManager.loadPlugin(
                            context,
                            dest,
                            PluginData(
                                fileName,
                                null,
                                false,
                                dest.absolutePath,
                                PLUGIN_VERSION_NOT_SET
                            )
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to install bundled plugin $fileName")
                    logError(t)
                }
            }

            // First launch: point the home at a Hindi anime provider.
            if (DataStoreHelper.currentHomePage.isNullOrBlank()) {
                val home = APIHolder.apis.firstOrNull { it.name == "AnimeWorldIndia" }
                    ?: APIHolder.apis.firstOrNull {
                        it.lang == "hi" && it.supportedTypes.any { type ->
                            type == TvType.Anime || type == TvType.AnimeMovie
                        }
                    }
                    ?: APIHolder.apis.firstOrNull {
                        it.supportedTypes.any { type ->
                            type == TvType.Anime || type == TvType.AnimeMovie
                        }
                    }
                if (home != null) {
                    DataStoreHelper.currentHomePage = home.name
                    MainActivity.reloadHomeEvent(true)
                }
            }
        } catch (t: Throwable) {
            logError(t)
        } finally {
            installInProgress = false
        }
    }
}

/**
 * First-launch defaults + home language section for Hindi Anime.
 *
 * Language codes used across the app: "all" (every language),
 * "hi" (Hindi Dub), "en" (English), "ja" (Japanese subtitles via the
 * English sources, which carry Japanese audio + English subs).
 */
object HindiAnimeDefaults {
    /** Key in the default SharedPreferences holding the home language choice. */
    const val HINDI_ANIME_LANG_KEY = "hindi_anime_language"

    const val LANG_ALL = "all"
    const val LANG_HINDI = "hi"
    const val LANG_ENGLISH = "en"
    const val LANG_JAPANESE = "ja"

    /** Anime TvType ordinals used by the preferred-media-type setting. */
    private val ANIME_MEDIA_TYPES = setOf(
        TvType.AnimeMovie.ordinal.toString(),
        TvType.Anime.ordinal.toString(),
        TvType.OVA.ordinal.toString(),
    )

    fun getLanguage(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(HINDI_ANIME_LANG_KEY, LANG_ALL) ?: LANG_ALL
    }

    /**
     * Runs once per install: anime-only media types, skip the stock setup
     * wizard (bundled plugins are already there), default language = all.
     * Also marks the bundled install as in progress synchronously so the
     * setup gate in MainActivity never flashes the extensions screen.
     */
    fun applyFirstLaunchDefaults(context: Context) {
        BundledPlugins.installInProgress = true
        try {
            if (getKey<Boolean>(HAS_DONE_SETUP_KEY, false) != true) {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putStringSet(
                        context.getString(R.string.prefer_media_type_key),
                        ANIME_MEDIA_TYPES
                    )
                    .putString(HINDI_ANIME_LANG_KEY, LANG_ALL)
                    .apply()
                setKey(HAS_DONE_SETUP_KEY, true)
                Log.i("HindiAnime", "First-launch defaults applied (anime-only, setup skipped)")
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /**
     * Apply a home language: persist the choice, filter providers by that
     * language, switch the homepage to the first matching anime provider and
     * reload the home.
     */
    fun applyLanguage(context: Context, lang: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(HINDI_ANIME_LANG_KEY, lang).apply()
        val providerLang = when (lang) {
            LANG_HINDI -> setOf("hi")
            LANG_ENGLISH -> setOf("en")
            // Japanese audio comes with the English sources (subs); there is
            // no separate Japanese provider in the bundle.
            LANG_JAPANESE -> setOf("en")
            else -> setOf("universal")
        }
        prefs.edit()
            .putStringSet(context.getString(R.string.provider_lang_key), providerLang)
            .apply()

        val wanted = if (lang == LANG_JAPANESE) "en" else providerLang.first()
        val api = APIHolder.apis.firstOrNull {
            it.lang == wanted && it.supportedTypes.any { type ->
                type == TvType.Anime || type == TvType.AnimeMovie
            }
        } ?: APIHolder.apis.firstOrNull { it.lang == wanted }
        if (api != null) {
            DataStoreHelper.currentHomePage = api.name
        }
        MainActivity.reloadHomeEvent(true)
    }
}
