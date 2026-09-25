package com.nuvio.tv.ui.screens.player.seekpreview

import android.content.Context
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The user's own Seekr API key. The built-in key is shared by everyone and Seekr's free tier
 * caps it at 40 distinct titles a day, so people can paste their own; empty means built-in.
 */
internal object SeekrKeyPreferences {
    private const val PREFS = "nuvio_seekr_settings"
    private const val KEY_USER_API_KEY = "user_api_key"

    private val _userKey = MutableStateFlow("")
    val userKey: StateFlow<String> = _userKey.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _userKey.value = prefs(context).getString(KEY_USER_API_KEY, null).orEmpty()
            loaded = true
        }
    }

    fun setUserKey(context: Context, key: String) {
        val trimmed = key.trim()
        _userKey.value = trimmed
        loaded = true
        prefs(context).edit().putString(KEY_USER_API_KEY, trimmed).apply()
    }

    /** The user's key, else the built-in BuildConfig.SEEKR_API_KEY. */
    fun effectiveKey(context: Context): String {
        ensureLoaded(context)
        return _userKey.value.ifBlank { BuildConfig.SEEKR_API_KEY }
    }

    /** True when Seekr accepts [key]; false when it rejects it or can't be reached. */
    suspend fun validate(key: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.seekr.tv/v1/keys/validate")
                .header("X-API-Key", key)
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful &&
                    response.body?.string().orEmpty().replace(" ", "").contains("\"valid\":true")
            }
        }.getOrDefault(false)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
