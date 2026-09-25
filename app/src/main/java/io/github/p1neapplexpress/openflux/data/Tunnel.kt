package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.Serializable

@Serializable
data class Tunnel(
    val id: Long,
    val name: String,
    val transportType: String,
    val transportConnPayload: List<String>,
    /** Shared secret for OpenFlux's AES-256-GCM transport encryption; null means unencrypted. */
    val encryptionKey: String? = null,
    /** Removable OpenFlux Control subscription URL; persisted in encrypted preferences. */
    val subscriptionUrl: String? = null,
    /** Encrypted-at-rest app profile cache used to materialize the v2 pool config at launch. */
    val poolConfigJson: String? = null,
    val mode: String = "standalone",
    val deviceId: String? = null,
    val deviceCredential: String? = null,
    val cacheUpdatedAt: String? = null,
    val cacheOffline: Boolean = false,
)
