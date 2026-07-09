package com.idos.pos.licensing

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val TEST_ISSUER = "idos-vendor-prod"
private const val TEST_GRACE_DAYS = 3L
private const val INSTALLATION_ID = "installation-id-abc123"

/**
 * RED/GREEN (task 1.2/1.3): [LicenseVerifier.verify] against design.md's
 * 6-step algorithm ("Claim shape + verification algorithm") —
 * specs/license-verification/spec.md scenarios: signature valid/tampered,
 * product match/mismatch, machine-id match/mismatch, `limits{}` ignored,
 * expiry VALID/boundary/GRACE/EXPIRED, malformed JWS.
 *
 * Signs test fixtures with a throwaway JVM-generated RSA keypair (never the
 * real vendor key — `res/raw/idos_vendor_pub` is untouched by this PR) via
 * [buildTestJws]. Runs under Robolectric because `org.json.JSONObject` (used
 * by production `LicenseVerifier`) is an Android-framework class, matching
 * every other test in this suite (e.g. [com.idos.pos.core.db.PosDatabaseSeedTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseVerifierTest {

    private lateinit var publicKey: PublicKey
    private lateinit var privateKey: PrivateKey
    private lateinit var verifier: LicenseVerifier

    @Before
    fun setUp() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        publicKey = keyPair.public
        privateKey = keyPair.private
        verifier = LicenseVerifier(publicKey = publicKey, expectedIssuer = TEST_ISSUER, graceDays = TEST_GRACE_DAYS)
    }

    private fun payload(
        exp: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(30),
        product: String = LicenseVerifier.PRODUCT_ANDROID_POS,
        machineId: String = INSTALLATION_ID,
        issuer: String = TEST_ISSUER,
        withLimits: Boolean = false
    ): String {
        val json = JSONObject()
            .put("iss", issuer)
            .put("sub", "license-001")
            .put("iat", LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toEpochSecond())
            .put("exp", exp.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
            .put("version", 1)
            .put("plan", "none")
            .put("business", JSONObject().put("id", "biz-1").put("name", "Acme Bar").put("taxId", "TAX-1"))
            .put("machineId", machineId)
            .put("product", product)
        if (withLimits) {
            json.put("limits", JSONObject().put("maxStores", 1).put("maxUsers", 1).put("maxProducts", 100))
        }
        return json.toString()
    }

    private fun sign(headerJson: String, payloadJson: String, key: PrivateKey): String {
        val headerB64 = base64Url(headerJson.toByteArray(Charsets.UTF_8))
        val payloadB64 = base64Url(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)
        val signatureBytes = Signature.getInstance("SHA256withRSA").apply {
            initSign(key)
            update(signingInput)
        }.sign()
        return "$headerB64.$payloadB64.${base64Url(signatureBytes)}"
    }

    private fun buildTestJws(
        payloadJson: String = payload(),
        headerJson: String = JSONObject().put("alg", "RS256").put("typ", "JWT").toString(),
        key: PrivateKey = privateKey
    ): String = sign(headerJson, payloadJson, key)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    // --- Requirement: JWS Signature Verification ---

    @Test
    fun verify_withValidSignatureAndMatchingClaims_returnsValid() {
        val jws = buildTestJws()

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.VALID, result.status)
        assertNotNull(result.claims)
        assertEquals("license-001", result.claims?.licenseId)
    }

    @Test
    fun verify_withTamperedPayload_returnsInvalidSignature() {
        val validJws = buildTestJws()
        val (header, _, signature) = validJws.split(".")
        val tamperedPayload = base64Url(payload(machineId = "some-other-device").toByteArray(Charsets.UTF_8))
        val tamperedJws = "$header.$tamperedPayload.$signature"

        val result = verifier.verify(tamperedJws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status)
        assertNull(result.claims)
    }

    @Test
    fun verify_signedByAnUntrustedKey_returnsInvalidSignature() {
        val untrustedKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val jws = buildTestJws(key = untrustedKey)

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.INVALID_SIGNATURE, result.status)
    }

    // --- Requirement: Product Claim Enforcement ---

    @Test
    fun verify_withDesktopProductClaim_returnsWrongProduct() {
        val jws = buildTestJws(payloadJson = payload(product = "backend"))

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.WRONG_PRODUCT, result.status)
    }

    @Test
    fun verify_withMissingProductClaim_returnsWrongProduct() {
        val payloadJson = JSONObject(payload()).apply { remove("product") }.toString()
        val jws = buildTestJws(payloadJson = payloadJson)

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.WRONG_PRODUCT, result.status)
    }

    @Test
    fun verify_withUnexpectedIssuer_returnsWrongProduct() {
        val jws = buildTestJws(payloadJson = payload(issuer = "some-other-vendor"))

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.WRONG_PRODUCT, result.status)
    }

    // --- Machine-ID binding ---

    @Test
    fun verify_withMatchingMachineId_returnsValid() {
        val jws = buildTestJws(payloadJson = payload(machineId = INSTALLATION_ID))

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.VALID, result.status)
    }

    @Test
    fun verify_withDifferentMachineId_returnsMachineMismatch() {
        val jws = buildTestJws(payloadJson = payload(machineId = "a-different-installation-id"))

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.MACHINE_MISMATCH, result.status)
    }

    // --- Requirement: `limits{}` Claim Is Ignored ---

    @Test
    fun verify_withLegacyLimitsClaimPresent_stillVerifiesSuccessfully() {
        val jws = buildTestJws(payloadJson = payload(withLimits = true))

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.VALID, result.status)
    }

    // --- Requirement: Status Evaluation From Expiration ---

    @Test
    fun verify_onExpirationDayItself_returnsValid() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val jws = buildTestJws(payloadJson = payload(exp = today))

        val result = verifier.verify(jws, INSTALLATION_ID, today, compromised = false)

        assertEquals(LicenseStatus.VALID, result.status)
    }

    @Test
    fun verify_dayAfterExpiration_returnsInGracePeriod() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val jws = buildTestJws(payloadJson = payload(exp = today.minusDays(1)))

        val result = verifier.verify(jws, INSTALLATION_ID, today, compromised = false)

        assertEquals(LicenseStatus.IN_GRACE_PERIOD, result.status)
    }

    @Test
    fun verify_onLastDayOfGraceWindow_returnsInGracePeriod() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val jws = buildTestJws(payloadJson = payload(exp = today.minusDays(TEST_GRACE_DAYS)))

        val result = verifier.verify(jws, INSTALLATION_ID, today, compromised = false)

        assertEquals(LicenseStatus.IN_GRACE_PERIOD, result.status)
    }

    @Test
    fun verify_beyondGraceWindow_returnsExpired() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val jws = buildTestJws(payloadJson = payload(exp = today.minusDays(TEST_GRACE_DAYS + 1)))

        val result = verifier.verify(jws, INSTALLATION_ID, today, compromised = false)

        assertEquals(LicenseStatus.EXPIRED, result.status)
    }

    // --- Sticky COMPROMISED override (design.md step 6) ---

    @Test
    fun verify_withCompromisedFlagTrue_returnsCompromised_evenWhenNotExpired() {
        val jws = buildTestJws()

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = true)

        assertEquals(LicenseStatus.COMPROMISED, result.status)
    }

    // --- Malformed JWS ---

    @Test
    fun verify_withWrongNumberOfParts_returnsMalformed() {
        val result = verifier.verify("only.two", INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.MALFORMED, result.status)
    }

    @Test
    fun verify_withInvalidBase64Segment_returnsMalformed() {
        val result = verifier.verify(
            "not-valid-base64!!.also-not-valid!!.sig",
            INSTALLATION_ID,
            LocalDate.now(ZoneOffset.UTC),
            compromised = false
        )

        assertEquals(LicenseStatus.MALFORMED, result.status)
    }

    @Test
    fun verify_withValidBase64ButNonJsonPayload_returnsMalformed() {
        // sign() base64url-encodes whatever payload string it's given, so this
        // still produces a validly-signed-but-non-JSON payload segment.
        val jws = sign(
            headerJson = JSONObject().put("alg", "RS256").toString(),
            payloadJson = "not-json-at-all",
            key = privateKey
        )

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.MALFORMED, result.status)
    }

    @Test
    fun verify_withUnsupportedAlgHeader_returnsMalformed() {
        val jws = sign(
            headerJson = JSONObject().put("alg", "HS256").toString(),
            payloadJson = payload(),
            key = privateKey
        )

        val result = verifier.verify(jws, INSTALLATION_ID, LocalDate.now(ZoneOffset.UTC), compromised = false)

        assertEquals(LicenseStatus.MALFORMED, result.status)
    }
}
