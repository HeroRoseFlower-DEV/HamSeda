package com.hamseda.walkie

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamseda.walkie.audio.OpusCodec
import com.hamseda.walkie.crypto.SessionCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device sanity checks. Compiled in CI via `:app:assembleAndroidTest`;
 * executed on hardware/emulator (see docs/TEST_PLAN.md). These verify that
 * the platform crypto provider and the vendored Opus codec behave on a
 * real Android runtime — the JVM unit tests cannot prove that.
 */
@RunWith(AndroidJUnit4::class)
class HamSedaInstrumentedTest {

    @Test
    fun appContext_isCorrect() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.hamseda.walkie", ctx.packageName)
    }

    @Test
    fun platformCrypto_supportsRequiredPrimitives() {
        // ECDH P-256 + AES-256-GCM on the device's own provider.
        val a = SessionCrypto.generateEphemeralKeyPair()
        val b = SessionCrypto.generateEphemeralKeyPair()
        val secret = SessionCrypto.agreeSharedSecret(
            a, SessionCrypto.decodePublicKey(SessionCrypto.encodePublicKey(b.public)),
        )
        val keys = SessionCrypto.deriveKeys(secret, SessionCrypto.newNonce16(), SessionCrypto.newNonce16())
        val aad = "instrumented".toByteArray()
        val sealed = SessionCrypto.seal(keys.audioKey, aad, "ping".toByteArray())
        assertNotNull(SessionCrypto.open(keys.audioKey, aad, sealed))
        keys.wipe()
    }

    @Test
    fun opusCodec_roundtripOnDevice() {
        val codec = OpusCodec()
        val pcm = ShortArray(320) { (it % 50).toShort() }
        val decoded = codec.decode(codec.encode(pcm))
        assertEquals(320, decoded.size)
        codec.close()
    }
}
