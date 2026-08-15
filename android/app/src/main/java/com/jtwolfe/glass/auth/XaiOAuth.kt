package com.jtwolfe.glass.auth

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * xAI OAuth device-code flow client.
 *
 * Matches Elyra's xai_oauth.py protocol:
 * - OIDC discovery from https://auth.x.ai/.well-known/openid-configuration
 * - Device authorization at device_authorization_endpoint
 * - Token polling at token_endpoint with urn:ietf:params:oauth:grant-type:device_code
 * - Refresh via refresh_token grant
 *
 * The public client ID is shared with Grok CLI / OpenClaw.
 */
class XaiOAuth(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private var cachedDiscovery: DiscoveryDocument? = null

    fun fetchDiscovery(): DiscoveryDocument {
        cachedDiscovery?.let { return it }

        val request = Request.Builder()
            .url(OIDC_DISCOVERY_URL)
            .header("Accept", "application/json")
            .get()
            .build()

        val doc = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return fallbackDiscovery()
                }
                val body = response.body?.string() ?: return fallbackDiscovery()
                val json = JSONObject(body)
                DiscoveryDocument(
                    issuer = json.optString("issuer", OIDC_ISSUER),
                    deviceAuthorizationEndpoint = json.optString(
                        "device_authorization_endpoint",
                        DEVICE_CODE_URL,
                    ),
                    tokenEndpoint = json.optString("token_endpoint", TOKEN_URL),
                )
            }
        } catch (_: Exception) {
            fallbackDiscovery()
        }

        cachedDiscovery = doc
        return doc
    }

    private fun fallbackDiscovery() = DiscoveryDocument(
        issuer = OIDC_ISSUER,
        deviceAuthorizationEndpoint = DEVICE_CODE_URL,
        tokenEndpoint = TOKEN_URL,
    )

    fun requestDeviceCode(discovery: DiscoveryDocument = fetchDiscovery()): DeviceCodeResponse {
        val formBody = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()

        val request = Request.Builder()
            .url(discovery.deviceAuthorizationEndpoint)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IOException("Empty device code response")

            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                val error = json?.optString("error") ?: "http_${response.code}"
                throw IOException("Device code request failed: $error")
            }

            val json = JSONObject(body)
            val deviceCode = json.optString("device_code")
            val userCode = json.optString("user_code")
            val verificationUri = json.optString("verification_uri")
                .ifBlank { json.optString("verification_url") }

            if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
                throw IOException("Incomplete device code response")
            }

            return DeviceCodeResponse(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                verificationUriComplete = json.optString("verification_uri_complete")
                    .ifBlank { json.optString("verification_url_complete") }
                    .takeIf { it.isNotBlank() },
                expiresIn = json.optInt("expires_in", 600),
                interval = json.optInt("interval", 5).coerceIn(1, 60),
            )
        }
    }

    fun pollDeviceToken(
        deviceCode: String,
        discovery: DiscoveryDocument = fetchDiscovery(),
    ): TokenPollResult {
        val formBody = FormBody.Builder()
            .add("grant_type", DEVICE_GRANT)
            .add("device_code", deviceCode)
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(discovery.tokenEndpoint)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                parseTokenResponse(response.code, JSONObject(body))
            }
        } catch (e: IOException) {
            TokenPollResult.networkError()
        }
    }

    fun refreshAccessToken(
        refreshToken: String,
        discovery: DiscoveryDocument = fetchDiscovery(),
    ): TokenPollResult {
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(discovery.tokenEndpoint)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                parseTokenResponse(response.code, JSONObject(body))
            }
        } catch (e: IOException) {
            TokenPollResult.networkError()
        }
    }

    private fun parseTokenResponse(code: Int, json: JSONObject): TokenPollResult {
        if (code == 200 && json.has("access_token")) {
            return TokenPollResult(
                ok = true,
                pending = false,
                slowDown = false,
                accessToken = json.optString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresIn = json.optInt("expires_in").takeIf { it > 0 },
                idToken = json.optString("id_token").takeIf { it.isNotBlank() },
                error = null,
                detail = null,
            )
        }

        val error = json.optString("error").lowercase()
        return when (error) {
            "authorization_pending" -> TokenPollResult(
                ok = false,
                pending = true,
                slowDown = false,
                error = error,
                detail = "authorization_pending",
            )
            "slow_down" -> TokenPollResult(
                ok = false,
                pending = true,
                slowDown = true,
                error = error,
                detail = "slow_down",
            )
            "access_denied", "authorization_declined" -> TokenPollResult(
                ok = false,
                pending = false,
                slowDown = false,
                error = error,
                detail = "oauth_denied",
            )
            "expired_token", "expired_token_code" -> TokenPollResult(
                ok = false,
                pending = false,
                slowDown = false,
                error = error,
                detail = "oauth_device_expired",
            )
            "invalid_grant" -> TokenPollResult(
                ok = false,
                pending = false,
                slowDown = false,
                error = error,
                detail = "oauth_reauth_required",
            )
            else -> TokenPollResult(
                ok = false,
                pending = false,
                slowDown = false,
                error = error.ifBlank { "http_$code" },
                detail = "oauth_failed",
            )
        }
    }

    companion object {
        const val OIDC_ISSUER = "https://auth.x.ai"
        const val OIDC_DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
        const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
        const val TOKEN_URL = "https://auth.x.ai/oauth2/token"
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    }
}

data class DiscoveryDocument(
    val issuer: String,
    val deviceAuthorizationEndpoint: String,
    val tokenEndpoint: String,
)

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresIn: Int,
    val interval: Int,
) {
    val expiresAt: Instant get() = Instant.now().plusSeconds(expiresIn.toLong())
}

data class TokenPollResult(
    val ok: Boolean,
    val pending: Boolean,
    val slowDown: Boolean,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Int? = null,
    val idToken: String? = null,
    val error: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun networkError() = TokenPollResult(
            ok = false,
            pending = false,
            slowDown = false,
            error = "network",
            detail = "oauth_network_error",
        )
    }
}
