package io.github.p1neapplexpress.openflux.data

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionImporterTest {
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext() = this
    }

    @Test
    fun `export QR config preserves transport mode and device identity`() {
        val raw = """
            {
              "schema_version": 1,
              "device_id": "device-123",
              "device_credential": "${"a".repeat(64)}",
              "subscription_url": "https://of.example/s/${"b".repeat(43)}",
              "client_configs": [{
                "schema_version": 1,
                "mode": "transport",
                "protocol_version": 2,
                "pool_id": "happ-pool",
                "documents": [{"id":"doc-a","url":"https://disk.yandex.ru/i/example"}],
                "client_id": "device-123",
                "client_key": "${"a".repeat(64)}",
                "tcp_target": "127.0.0.1:19100"
              }]
            }
        """.trimIndent()

        val result = SubscriptionImporter(context).import(raw)
        assertTrue("expected a successful import, got $result", result is SubscriptionImportResult.Success)
        val tunnel = (result as SubscriptionImportResult.Success).tunnels.single()
        assertEquals("transport", tunnel.mode)
        assertEquals("device-123", tunnel.deviceId)
        assertTrue(tunnel.transportConnPayload.contains("--openflux-mode=transport"))
    }
}
