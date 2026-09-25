package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

sealed interface SubscriptionImportResult {
    data class Success(val tunnels: List<Tunnel>) : SubscriptionImportResult
    data object Revoked : SubscriptionImportResult
    data class TemporaryFailure(val message: String) : SubscriptionImportResult
}

/** Imports an OpenFlux subscription or a per-device pool JSON without logging any tokens/keys. */
class SubscriptionImporter(context: Context) {
    private val app = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun import(raw: String, cached: List<Tunnel> = emptyList()): SubscriptionImportResult {
        val source = raw.trim()
        if (source.length !in 2..1_048_576) return SubscriptionImportResult.TemporaryFailure("Configuration is empty or too large")
        if (source.startsWith("https://", ignoreCase = true) || isLocalHttpUrl(source)) {
            return refreshSubscription(source, cached)
        }
        return importJson(source)
    }

    fun refreshSubscription(url: String, cached: List<Tunnel>): SubscriptionImportResult {
        val parsed = try { validateURL(url) } catch (_: Exception) {
            return SubscriptionImportResult.TemporaryFailure("Subscription URL is invalid")
        }
        if (!parsed.path.startsWith("/s/")) return SubscriptionImportResult.TemporaryFailure("This is not an OpenFlux subscription URL")
        val token = parsed.path.substringAfterLast('/')
        if (token.length !in 32..128) return SubscriptionImportResult.TemporaryFailure("Subscription URL is invalid")
        val old = cached.firstOrNull { it.subscriptionUrl == url && !it.deviceId.isNullOrBlank() && !it.deviceCredential.isNullOrBlank() }

        val profileResult = request("GET", url)
        if (profileResult.code == 404 || profileResult.code == 410 || profileResult.code == 403) return SubscriptionImportResult.Revoked
        if (profileResult.code != 200) return SubscriptionImportResult.TemporaryFailure("Subscription refresh failed (HTTP ${profileResult.code})")
        val profile = try { json.decodeFromString<SubscriptionProfile>(profileResult.body) } catch (_: Exception) {
            return SubscriptionImportResult.TemporaryFailure("Subscription response is malformed")
        }
        if (profile.schemaVersion != 1 || profile.user.enabled == false || profile.pools.isEmpty() || profile.pools.any { it.documents.isEmpty() }) {
            return SubscriptionImportResult.TemporaryFailure("Subscription response did not pass validation")
        }
        val registrationURL = try { validateURL(profile.deviceRegistrationUrl).toString() } catch (_: Exception) {
            return SubscriptionImportResult.TemporaryFailure("Device registration endpoint is invalid")
        }
        val body = DeviceRegistrationRequest(
            subscriptionToken = token,
            name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(128).ifEmpty { "Android device" },
            platform = "android",
            deviceId = old?.deviceId,
            credential = old?.deviceCredential,
        )
        var registered = request("POST", registrationURL, json.encodeToString(body))
        if (registered.code == 403 && old != null) {
            // A revoked device key can be re-enrolled through the still-valid user subscription.
            registered = request("POST", registrationURL, json.encodeToString(body.copy(deviceId = null, credential = null)))
        }
        if (registered.code == 404 || registered.code == 410) return SubscriptionImportResult.Revoked
        if (registered.code != 200 && registered.code != 201) return SubscriptionImportResult.TemporaryFailure("Device registration failed (HTTP ${registered.code})")
        val device = try { json.decodeFromString<DeviceRegistrationResponse>(registered.body) } catch (_: Exception) {
            return SubscriptionImportResult.TemporaryFailure("Device registration response is malformed")
        }
        if (device.deviceId.isBlank() || device.credential.isBlank() || device.clientConfigs.isEmpty()) {
            return SubscriptionImportResult.TemporaryFailure("Device registration response is incomplete")
        }
        val poolNames = profile.pools.associate { it.id to it.name }
        val newTunnels = device.clientConfigs.mapNotNull { config ->
            val mode = config.mode.ifBlank { profile.pools.firstOrNull { it.id == config.poolId }?.mode.orEmpty() }.ifBlank { "standalone" }
            if (mode !in setOf("standalone", "transport") || config.documents.isEmpty()) return@mapNotNull null
            if (mode == "transport" && config.tcpTarget.isNullOrBlank()) return@mapNotNull null
            createTunnel(
                name = "${profile.user.username} · ${poolNames[config.poolId] ?: config.poolId}",
                config = config.copy(mode = mode),
                mode = mode,
                subscriptionUrl = url,
                deviceId = device.deviceId,
                credential = device.credential,
                updatedAt = profile.fetchedAt,
            )
        }
        if (newTunnels.isEmpty() || newTunnels.size != device.clientConfigs.size) return SubscriptionImportResult.TemporaryFailure("No valid client pools were returned")
        return SubscriptionImportResult.Success(newTunnels)
    }

    private fun importJson(raw: String): SubscriptionImportResult {
        val root = try { json.parseToJsonElement(raw).jsonObject } catch (_: Exception) {
            return SubscriptionImportResult.TemporaryFailure("QR/file is not a supported JSON configuration")
        }
        // Existing app QR format remains supported.
        runCatching { json.decodeFromString<Tunnel>(raw) }.getOrNull()?.let {
            return SubscriptionImportResult.Success(listOf(it))
        }
        val embedded = root["client_configs"]?.let { runCatching { it.jsonArray }.getOrNull() }
        if (embedded != null) {
            val url = root["subscription_url"]?.jsonPrimitive?.contentOrNull
            val deviceId = root["device_id"]?.jsonPrimitive?.contentOrNull
            val credential = root["device_credential"]?.jsonPrimitive?.contentOrNull
            val tunnels = embedded.mapNotNull { item ->
                val config = runCatching { json.decodeFromJsonElement<DevicePoolConfig>(item) }.getOrNull() ?: return@mapNotNull null
                val mode = config.mode.ifBlank { root["mode"]?.jsonPrimitive?.contentOrNull ?: "standalone" }
                createTunnel(config.poolId, config, mode, url, deviceId, credential, null)
            }
            if (tunnels.isNotEmpty() && tunnels.size == embedded.size) return SubscriptionImportResult.Success(tunnels)
            return SubscriptionImportResult.TemporaryFailure("Exported config contains an invalid pool")
        }
        val config = runCatching { json.decodeFromString<DevicePoolConfig>(raw) }.getOrNull()
            ?: return SubscriptionImportResult.TemporaryFailure("JSON is not an OpenFlux pool config")
        if (config.poolId.isBlank() || config.clientId.isBlank() || config.clientKey.isBlank() || config.documents.isEmpty()) {
            return SubscriptionImportResult.TemporaryFailure("Pool config is missing a device key or documents")
        }
        if (config.mode == "transport" && config.tcpTarget.isNullOrBlank()) return SubscriptionImportResult.TemporaryFailure("Transport config has no TCP target")
        return SubscriptionImportResult.Success(listOf(createTunnel(config.poolId, config.mode.ifBlank { "standalone" }, config, null, config.clientId, config.clientKey, null)))
    }

    private fun createTunnel(
        name: String,
        config: DevicePoolConfig,
        mode: String,
        subscriptionUrl: String?,
        deviceId: String?,
        credential: String?,
        updatedAt: String?,
    ): Tunnel {
        val poolJSON = json.encodeToString(config.copy(mode = mode))
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(poolJSON.toByteArray(Charsets.UTF_8))
        val stable = UUID.nameUUIDFromBytes("${subscriptionUrl.orEmpty()}#${config.poolId}".toByteArray()).leastSignificantBits
        return Tunnel(
            id = stable,
            name = name.take(128),
            transportType = "yandex",
            transportConnPayload = listOf("--role", "client", "--transport", "yandex", "--pool-config-json=$encoded", "--openflux-mode=$mode"),
            subscriptionUrl = subscriptionUrl,
            poolConfigJson = poolJSON,
            mode = mode,
            deviceId = deviceId ?: config.clientId,
            deviceCredential = credential ?: config.clientKey,
            cacheUpdatedAt = updatedAt ?: java.time.Instant.now().toString(),
        )
    }

    private fun createTunnel(poolId: String, mode: String, config: DevicePoolConfig, url: String?, deviceId: String?, credential: String?, updatedAt: String?) =
        createTunnel(poolId, config, mode, url, deviceId, credential, updatedAt)

    private fun request(method: String, target: String, body: String? = null): Response {
        var conn: HttpURLConnection? = null
        return try {
            val uri = validateURL(target)
            conn = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenFlux-Android")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 1_048_576) return Response(code, "")
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            Response(code, text)
        } catch (_: Exception) {
            Response(0, "")
        } finally {
            conn?.disconnect()
        }
    }

    private fun validateURL(value: String): URI {
        require(value.length <= 2048)
        val uri = URI(value)
        require(uri.userInfo == null && uri.host != null && uri.rawUserInfo == null)
        require(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && (uri.host == "127.0.0.1" || uri.host == "localhost")))
        return uri
    }

    private fun isLocalHttpUrl(value: String): Boolean = runCatching { validateURL(value).scheme.equals("http", true) }.getOrDefault(false)
    private data class Response(val code: Int, val body: String)

    @Serializable private data class SubscriptionProfile(
        @SerialName("schema_version") val schemaVersion: Int,
        val user: SubscriptionUser,
        @SerialName("device_registration_url") val deviceRegistrationUrl: String,
        val pools: List<SubscriptionPool>,
        @SerialName("fetched_at") val fetchedAt: String = "",
    )
    @Serializable private data class SubscriptionUser(val username: String, val enabled: Boolean? = true)
    @Serializable private data class SubscriptionPool(val id: String, val name: String, val mode: String = "standalone", val documents: List<PoolDocument>)
    @Serializable private data class PoolDocument(val id: String, val url: String)
    @Serializable private data class DeviceRegistrationRequest(
        @SerialName("subscription_token") val subscriptionToken: String,
        val name: String,
        val platform: String,
        @SerialName("device_id") val deviceId: String? = null,
        val credential: String? = null,
    )
    @Serializable private data class DeviceRegistrationResponse(
        @SerialName("device_id") val deviceId: String,
        val credential: String,
        @SerialName("client_configs") val clientConfigs: List<DevicePoolConfig>,
    )
    @Serializable private data class DevicePoolConfig(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        @SerialName("protocol_version") val protocolVersion: Int = 2,
        @SerialName("pool_id") val poolId: String,
        val mode: String = "standalone",
        val documents: List<PoolDocument>,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_key") val clientKey: String,
        @SerialName("tcp_target") val tcpTarget: String? = null,
    )
}
