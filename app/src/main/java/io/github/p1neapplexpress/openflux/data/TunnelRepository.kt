package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit
import io.github.p1neapplexpress.openflux.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelRepository(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = encryptedPreferences(app)

    init { migratePlaintextPreferences() }

    private fun encryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "openflux-profile-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun migratePlaintextPreferences() {
        val old = app.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)
        if (!prefs.contains(Constants.PREF_TUNNELS_KEY)) {
            val edit = prefs.edit()
            old.all.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                }
            }
            if (!edit.commit()) return
        }
        old.edit().clear().commit()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Tunnel> {
        val raw = prefs.getString(Constants.PREF_TUNNELS_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Tunnel>>(raw) }
            .getOrElse { emptyList() }
    }

    fun save(tunnels: List<Tunnel>) {
        prefs.edit {
            putString(Constants.PREF_TUNNELS_KEY, json.encodeToString(tunnels))
        }
    }

    /** ID последнего выбранного туннеля, или null если не выбран. */
    fun getSelectedId(): Long? {
        val v = prefs.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L)
        return if (v == -1L) null else v
    }

    fun setSelectedId(id: Long) {
        prefs.edit { putLong(Constants.PREF_SELECTED_TUNNEL_ID, id) }
    }

    /** Возвращает выбранный туннель, или первый из списка, или null. */
    fun getSelected(): Tunnel? {
        val all = load()
        if (all.isEmpty()) return null
        val id = getSelectedId()
        return all.firstOrNull { it.id == id } ?: all.first()
    }
}
