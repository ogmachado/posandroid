package com.idos.pos.licensing

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure, on-device RSA-SHA256 JWS verification for the offline Android license
 * — design.md "Claim shape + verification algorithm". Hand-rolled
 * `java.security.Signature` + `org.json` field-by-field parse, no third-party
 * JWT/JSON library (Decision A) — survives R8 with no keep rules.
 *
 * Deliberately takes [publicKey] as a constructor parameter instead of
 * loading `res/raw/idos_vendor_pub` itself: the DER→[PublicKey] decode step
 * (Decision B, `X509EncodedKeySpec`) lives in the pure, Context-free
 * [loadPublicKey] companion function. Wiring [loadPublicKey] to the real
 * `res/raw/idos_vendor_pub` resource bytes (via `Context.resources
 * .openRawResource(...).readBytes()`) is `core/di/AppContainer.kt`'s job
 * (Phase 3, out of scope here) — this keeps [LicenseVerifier] fully testable
 * against a throwaway keypair without touching the real vendor key resource.
 *
 * [expectedIssuer] is likewise a required constructor parameter rather than
 * an internal constant: design.md's Decision table locks `graceDays`/
 * `toleranceSec` explicitly but leaves `expectedIssuer`'s concrete value
 * (reusing the backend's `idos-vendor-prod` default) to the production wiring
 * that constructs this class (Phase 3), keeping this class free of any
 * hardcoded production secret/config value.
 */
class LicenseVerifier(
    private val publicKey: PublicKey,
    private val expectedIssuer: String,
    private val graceDays: Long
) {

    companion object {
        /** Design.md claim shape: mandatory `product` claim value for this Android build. */
        const val PRODUCT_ANDROID_POS = "android-pos"

        private const val ALG_RS256 = "RS256"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

        private const val FIELD_ALG = "alg"
        private const val FIELD_ISS = "iss"
        private const val FIELD_SUB = "sub"
        private const val FIELD_IAT = "iat"
        private const val FIELD_EXP = "exp"
        private const val FIELD_PRODUCT = "product"
        private const val FIELD_MACHINE_ID = "machineId"
        private const val FIELD_BUSINESS = "business"
        private const val FIELD_BUSINESS_ID = "id"
        private const val FIELD_BUSINESS_NAME = "name"
        private const val FIELD_BUSINESS_TAX_ID = "taxId"

        /**
         * Decision B: X.509 `SubjectPublicKeyInfo` DER bytes -> [PublicKey].
         * Pure function of bytes — no `Context`/`R.raw` reference, so this
         * class stays testable without the real vendor key resource existing.
         */
        fun loadPublicKey(derBytes: ByteArray): PublicKey {
            val spec = X509EncodedKeySpec(derBytes)
            return KeyFactory.getInstance("RSA").generatePublic(spec)
        }
    }

    /** Claims trusted only once the signature has verified — spec.md: "rejected before any claim is trusted". */
    data class Claims(
        val licenseId: String,
        val issuedAt: Instant,
        val expirationDate: LocalDate,
        val businessId: String?,
        val businessName: String?,
        val businessTaxId: String?,
        val product: String,
        val machineId: String
    )

    data class Result(val status: LicenseStatus, val claims: Claims? = null)

    /**
     * Design.md 6-step algorithm:
     * 1. Split/base64url-decode → MALFORMED on structural failure.
     * 2. `alg == "RS256"` else MALFORMED.
     * 3. RSA-SHA256 signature verify → INVALID_SIGNATURE on failure.
     * 4. `iss`/`product` → WRONG_PRODUCT; `machineId` → MACHINE_MISMATCH.
     * 5. `limits{}` ignored (never read).
     * 6. `compromised` sticky → COMPROMISED; else VALID/IN_GRACE_PERIOD/EXPIRED from `exp`.
     */
    fun verify(jws: String, installationId: String, nowUtc: LocalDate, compromised: Boolean): Result {
        val parts = jws.split(".")
        if (parts.size != 3) return Result(LicenseStatus.MALFORMED)
        val (headerB64, payloadB64, signatureB64) = parts

        val headerJson = decodeBase64UrlToString(headerB64) ?: return Result(LicenseStatus.MALFORMED)
        val payloadJson = decodeBase64UrlToString(payloadB64) ?: return Result(LicenseStatus.MALFORMED)
        val signatureBytes = decodeBase64UrlToBytes(signatureB64) ?: return Result(LicenseStatus.MALFORMED)

        val header = parseJsonOrNull(headerJson) ?: return Result(LicenseStatus.MALFORMED)
        if (header.optString(FIELD_ALG) != ALG_RS256) return Result(LicenseStatus.MALFORMED)

        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)
        val signatureValid = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                update(signingInput)
            }.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
        if (!signatureValid) return Result(LicenseStatus.INVALID_SIGNATURE)

        val payload = parseJsonOrNull(payloadJson) ?: return Result(LicenseStatus.MALFORMED)

        val product = payload.optString(FIELD_PRODUCT)
        val issuer = payload.optString(FIELD_ISS)
        if (issuer != expectedIssuer || product != PRODUCT_ANDROID_POS) return Result(LicenseStatus.WRONG_PRODUCT)

        val machineId = payload.optString(FIELD_MACHINE_ID)
        if (machineId != installationId) return Result(LicenseStatus.MACHINE_MISMATCH)

        // limits{} claim (maxStores/maxUsers/maxProducts) is intentionally never read —
        // Android licensing is expiration-only (spec: "limits{} Claim Is Ignored").

        val claims = try {
            val business = payload.optJSONObject(FIELD_BUSINESS)
            Claims(
                licenseId = payload.getString(FIELD_SUB),
                issuedAt = Instant.ofEpochSecond(payload.getLong(FIELD_IAT)),
                expirationDate = Instant.ofEpochSecond(payload.getLong(FIELD_EXP))
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate(),
                businessId = business?.optString(FIELD_BUSINESS_ID),
                businessName = business?.optString(FIELD_BUSINESS_NAME),
                businessTaxId = business?.optString(FIELD_BUSINESS_TAX_ID),
                product = product,
                machineId = machineId
            )
        } catch (e: JSONException) {
            return Result(LicenseStatus.MALFORMED)
        }

        if (compromised) return Result(LicenseStatus.COMPROMISED, claims)

        val status = when {
            !nowUtc.isAfter(claims.expirationDate) -> LicenseStatus.VALID
            !nowUtc.isAfter(claims.expirationDate.plusDays(graceDays)) -> LicenseStatus.IN_GRACE_PERIOD
            else -> LicenseStatus.EXPIRED
        }
        return Result(status, claims)
    }

    private fun parseJsonOrNull(json: String): JSONObject? = try {
        JSONObject(json)
    } catch (e: JSONException) {
        null
    }

    private fun decodeBase64UrlToBytes(value: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(padBase64(value))
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun decodeBase64UrlToString(value: String): String? =
        decodeBase64UrlToBytes(value)?.toString(Charsets.UTF_8)

    /** JWS segments are unpadded base64url; `java.util.Base64`'s decoder expects padding. */
    private fun padBase64(value: String): String {
        val remainder = value.length % 4
        return if (remainder == 0) value else value + "=".repeat(4 - remainder)
    }
}
