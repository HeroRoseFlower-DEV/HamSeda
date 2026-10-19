package com.hamseda.walkie.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies peer-photo persistence on a real Android runtime: a photo whose
 * bytes match the advertised SHA-256 is stored; a hash mismatch discards the
 * bytes and writes nothing (TOFU integrity gate).
 */
@RunWith(AndroidJUnit4::class)
class ProfilePhotoStoreTest {

    private lateinit var store: ProfilePhotoStore

    @Before
    fun setup() {
        store = ProfilePhotoStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun teardown() = runTest {
        store.removePeerPhoto(FINGERPRINT)
    }

    @Test
    fun savePeerPhoto_acceptsMatchingHash() = runTest {
        val bytes = ByteArray(256) { it.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)

        val file = store.savePeerPhoto(FINGERPRINT, bytes, hash)

        assertNotNull(file)
        assertNotNull(store.peerPhotoFile(FINGERPRINT))
    }

    @Test
    fun savePeerPhoto_discardsHashMismatch() = runTest {
        val bytes = ByteArray(256) { it.toByte() }
        val wrongHash = ByteArray(32) { 0x7F } // not the SHA-256 of bytes

        val file = store.savePeerPhoto(FINGERPRINT, bytes, wrongHash)

        assertNull("mismatched photo must be discarded", file)
        assertNull("no file may be written on mismatch", store.peerPhotoFile(FINGERPRINT))
    }

    @Test
    fun deleteIfHashMatches_deletesOnlyOwnBytes() = runTest {
        val bytes = ByteArray(256) { it.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val otherHash = ByteArray(32) { 0x7F }
        val file = store.savePeerPhoto(FINGERPRINT, bytes, hash)!!
        // Wrong hash: a newer transfer overwrote the shared path — keep it.
        assertFalse(store.deleteIfHashMatches(file, otherHash))
        assertNotNull(store.peerPhotoFile(FINGERPRINT))
        // Matching hash: our stale bytes — safe to drop.
        assertTrue(store.deleteIfHashMatches(file, hash))
        assertNull(store.peerPhotoFile(FINGERPRINT))
    }

    private companion object {
        const val FINGERPRINT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    }
}
