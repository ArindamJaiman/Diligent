package dev.diligent.app.quotapulse

import dev.diligent.app.quotapulse.data.MetricKind
import dev.diligent.app.quotapulse.data.Provider
import dev.diligent.app.quotapulse.data.SourceState
import dev.diligent.app.quotapulse.sources.AnthropicAdminSource
import dev.diligent.app.quotapulse.sources.ConnectorCrypto
import dev.diligent.app.quotapulse.sources.ConnectorPairing
import dev.diligent.app.quotapulse.sources.ConnectorSource
import dev.diligent.app.quotapulse.sources.OpenAiAdminSource
import dev.diligent.app.quotapulse.sources.SourceOutcome
import dev.diligent.app.quotapulse.sources.parseRetryAfter
import dev.diligent.app.quotapulse.sync.UsageSyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class QuotaPulseSourcesTest {

    private val now = 1_790_000_000_000L
    private val key = ByteArray(32) { 7 }

    /** Same envelope the Node connector writes: QP1:base64url(iv | ciphertext | tag), AAD "quotapulse-v1". */
    private fun envelope(plaintext: String, key: ByteArray = this.key): String {
        val iv = ByteArray(12) { 3 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("quotapulse-v1".toByteArray())
        return "QP1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(iv + cipher.doFinal(plaintext.toByteArray()))
    }

    private fun gist(content: String) = """{"id":"abc","files":{"quotapulse.enc":{"content":"$content"}}}"""

    private val payload = """
        {"v":1,"host":"LAPTOP","collectedAt":$now,"providers":[
          {"provider":"CODEX","status":"OK","metrics":[
            {"key":"codex.primary","kind":"SUBSCRIPTION_QUOTA","title":"5-hour","percentLeft":58.4,"resetsAt":${now + 3_600_000},"measuredAt":${now - 10_000}},
            {"key":"codex.cost","kind":"API_COST","title":"X","amount":9.0,"measuredAt":$now},
            {"key":"claude.sneaky","kind":"SUBSCRIPTION_QUOTA","title":"X","percentLeft":10,"measuredAt":$now},
            {"key":"codex.over","kind":"SUBSCRIPTION_QUOTA","title":"X","percentLeft":140,"measuredAt":${now + 86_400_000}}
          ]},
          {"provider":"CLAUDE","status":"UNAVAILABLE","message":"No Claude Code data","metrics":[]},
          {"provider":"GEMINI","status":"OK","metrics":[]}
        ]}
    """.trimIndent()

    @Test
    fun `pairing code round trip and validation`() {
        val code = "QP1.0123456789abcdef0123." + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val pairing = ConnectorPairing.parse(code)
        assertNotNull(pairing)
        assertEquals("0123456789abcdef0123", pairing!!.gistId)
        assertTrue(pairing.key.contentEquals(key))

        assertNull(ConnectorPairing.parse("QP2.0123456789abcdef0123.AAAA"))
        assertNull(ConnectorPairing.parse("QP1.not-hex!.AAAA"))
        assertNull(ConnectorPairing.parse("QP1.0123456789abcdef0123.c2hvcnQ")) // key too short
    }

    @Test
    fun `decrypts connector envelope`() {
        assertEquals("hello", ConnectorCrypto.decrypt(envelope("hello"), key))
    }

    @Test
    fun `decrypts an envelope produced by the Node connector`() {
        // Output of quotapulse-connector.mjs encrypt(plaintext, key = 32 x 0x07, iv = 12 x 0x03).
        val node = "QP1:AwMDAwMDAwMDAwMDXtzVIWAZcmASLzAoyW3dQqJ4pc2Yt_cfQ7Li0peAJPnFhUcTrMKVcP64ezW9_td1CwZjtub0VBNJWl2BBHfz" +
            "SoeRVA7GwhFja9yVxJvTgWvfhusGfgI6WJ9z7vU8SwMu569V1N8CRy5e90h8Bo8xhvfwHR0W97Qrk8CcugYpZ8KBl46pRXKZCfVzeooCeOJM" +
            "_0n8pr33mjsuoiy3igeX1jdl30IUM9IlEJ3lkkC_j0MF0MvoQ_FC-_WY_Nb4K8IfUm7-gTVIVXD2bSNtpd0VvTgvIrL7gGtJ2BzOaBhdpNiT" +
            "bfGOFKrT6PqaUtMjQKc-6n3HzhL179a-e7-rAZ2zg_RnRgC2eFkW7zlL-XyL7pWxh1Cxpx4oiweFhTsv7Ald4IeHvsqoPNGGypPP4cdwygWtiA"
        assertTrue(ConnectorCrypto.decrypt(node, key).contains("\"detail\":\"plan: plus · ok\""))

        val outcome = ConnectorSource.decode(gist(node), key, now) as SourceOutcome.Success
        assertEquals("NODE", outcome.note)
        val primary = outcome.reports.getValue(Provider.CODEX).snapshots.single()
        assertEquals(42.0, primary.percentLeft!!, 0.0)
        assertEquals(1_790_003_600_000L, primary.resetsAt)
    }

    @Test
    fun `wrong key asks to pair again instead of showing garbage`() {
        val outcome = ConnectorSource.decode(gist(envelope(payload)), ByteArray(32) { 9 }, now)
        assertTrue(outcome is SourceOutcome.Failure)
        assertEquals(SourceState.AUTH_REQUIRED, (outcome as SourceOutcome.Failure).state)
    }

    @Test
    fun `payload is validated field by field`() {
        val outcome = ConnectorSource.decode(gist(envelope(payload)), key, now) as SourceOutcome.Success
        assertEquals("LAPTOP", outcome.note)

        val codex = outcome.reports.getValue(Provider.CODEX)
        assertEquals(SourceState.OK, codex.state)
        // API billing never comes from the connector; keys must match their provider.
        assertEquals(listOf("codex.primary", "codex.over"), codex.snapshots.map { it.metricKey })
        val primary = codex.snapshots.first()
        assertEquals(MetricKind.SUBSCRIPTION_QUOTA.name, primary.kind)
        assertEquals("5-HOUR", primary.title)
        assertEquals(58.4, primary.percentLeft!!, 0.001)
        val over = codex.snapshots.last()
        assertEquals(100.0, over.percentLeft!!, 0.0)
        assertTrue(over.measuredAt <= now + 5 * 60_000L) // future timestamps clamped

        val claude = outcome.reports.getValue(Provider.CLAUDE)
        assertEquals(SourceState.UNAVAILABLE, claude.state)
        assertEquals("No Claude Code data", claude.message)

        // Providers the connector can't measure are ignored.
        assertNull(outcome.reports[Provider.GEMINI])
    }

    @Test
    fun `unknown payload version is rejected`() {
        val outcome = ConnectorSource.parsePayload("""{"v":2,"collectedAt":1,"providers":[]}""", now)
        assertTrue(outcome is SourceOutcome.Failure)
    }

    @Test
    fun `anthropic usage and cost parsing follows the documented shape`() {
        val usage = """
            {"data":[
              {"starting_at":"2026-10-08T00:00:00Z","ending_at":"2026-10-09T00:00:00Z","results":[
                {"uncached_input_tokens":1500,"cache_creation":{"ephemeral_1h_input_tokens":10,"ephemeral_5m_input_tokens":5},
                 "cache_read_input_tokens":200,"output_tokens":500,"server_tool_use":{"web_search_requests":1}}]},
              {"starting_at":"2026-10-09T00:00:00Z","ending_at":"2026-10-10T00:00:00Z","results":[
                {"uncached_input_tokens":100,"cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},
                 "cache_read_input_tokens":0,"output_tokens":50}]}
            ],"has_more":true,"next_page":"page_abc"}
        """.trimIndent()
        val parsed = AnthropicAdminSource.parseUsage(listOf(usage), LocalDate.parse("2026-10-09"))
        assertEquals(2_365L, parsed.monthTokens)
        assertEquals(150L, parsed.todayTokens)
        assertEquals("page_abc", AnthropicAdminSource.nextPage(usage))

        val cost = """{"data":[{"starting_at":"x","results":[{"amount":"123.45","currency":"USD"},{"amount":"0.55","currency":"USD"}]}],"has_more":false,"next_page":null}"""
        assertEquals(124.0, AnthropicAdminSource.parseCostCents(cost), 1e-9) // cents → $1.24
        assertNull(AnthropicAdminSource.nextPage(cost))
    }

    @Test
    fun `openai usage and cost parsing follows the documented shape`() {
        val usage = """
            {"object":"page","data":[{"object":"bucket","start_time":1736616660,"end_time":1736640000,"results":[
              {"object":"organization.usage.completions.result","input_tokens":141201,"output_tokens":9756,
               "num_model_requests":470,"input_cached_tokens":0}]}],"has_more":false,"next_page":null}
        """.trimIndent()
        val parsed = OpenAiAdminSource.parseUsage(listOf(usage))
        assertEquals(150_957L, parsed.tokens)
        assertEquals(470L, parsed.requests)
        assertNull(OpenAiAdminSource.nextPage(usage))

        val costs = """{"data":[{"results":[{"object":"organization.costs.result","amount":{"value":0.13080438340307526,"currency":"usd"}}]}]}"""
        assertEquals(0.1308, OpenAiAdminSource.parseCostUsd(costs), 1e-4)
    }

    @Test
    fun `retry-after and backoff`() {
        assertEquals(30_000L, parseRetryAfter("30", now))
        assertNull(parseRetryAfter(null, now))
        assertEquals(60_000L, UsageSyncEngine.backoffDelay(1))
        assertEquals(120_000L, UsageSyncEngine.backoffDelay(2))
        assertEquals(30 * 60_000L, UsageSyncEngine.backoffDelay(20))
    }
}
