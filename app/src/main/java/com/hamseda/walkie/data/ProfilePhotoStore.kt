package com.hamseda.walkie.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * App-private profile photo storage. Photos never leave the device except
 * over the E2E-encrypted control channel (PROFILE_PHOTO frames) — they are
 * never uploaded anywhere, never in shared storage.
 *
 * - Own photo: `filesDir/profile/own.jpg`, downscaled to ~256px on import.
 * - Peer photos: `filesDir/profile/peers/<fingerprint>.jpg`, written only
 *   after the reassembled bytes verify against the SHA-256 advertised in
 *   the peer's PROFILE frame.
 */
class ProfilePhotoStore(private val appContext: Context) {

    private fun profileDir(): File = File(appContext.filesDir, "profile").apply { mkdirs() }
    private fun peersDir(): File = File(profileDir(), "peers").apply { mkdirs() }

    fun ownPhotoFile(): File? =
        File(profileDir(), "own.jpg").takeIf { it.exists() && it.length() > 0 }

    fun peerPhotoFile(fingerprint: String): File? {
        val safe = fingerprint.filter { it.isLetterOrDigit() }.take(64)
        if (safe.isEmpty()) return null
        return File(peersDir(), "$safe.jpg").takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Imports the user's photo from the system picker: downscales so the
     * long edge is at most [MAX_EDGE_PX], writes JPEG to app-private files.
     * Returns the file, or null on failure.
     */
    suspend fun importOwnPhoto(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val bytes = downscale(uri, MAX_EDGE_PX) ?: return@withContext null
            val out = File(profileDir(), "own.jpg")
            FileOutputStream(out).use { it.write(bytes) }
            out
        } catch (e: Exception) {
            Log.w(TAG, "importOwnPhoto failed", e)
            null
        }
    }

    suspend fun removeOwnPhoto(): Unit = withContext(Dispatchers.IO) {
        runCatching { File(profileDir(), "own.jpg").delete() }
    }

    /**
     * Saves a peer's photo after verifying [bytes] against the SHA-256
     * [expectedHash] from their PROFILE frame. Returns the file or null.
     */
    suspend fun savePeerPhoto(fingerprint: String, bytes: ByteArray, expectedHash: ByteArray): File? =
        withContext(Dispatchers.IO) {
            try {
                val safe = fingerprint.filter { it.isLetterOrDigit() }.take(64)
                if (safe.isEmpty()) return@withContext null
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                if (!actual.contentEquals(expectedHash)) {
                    Log.w(TAG, "peer photo hash mismatch; discarding")
                    return@withContext null
                }
                val out = File(peersDir(), "$safe.jpg")
                FileOutputStream(out).use { it.write(bytes) }
                out
            } catch (e: Exception) {
                Log.w(TAG, "savePeerPhoto failed", e)
                null
            }
        }

    suspend fun removePeerPhoto(fingerprint: String): Unit = withContext(Dispatchers.IO) {
        runCatching { peerPhotoFile(fingerprint)?.delete() }
    }

    /** SHA-256 of raw bytes; used to advertise the own photo in PROFILE. */
    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Decodes [uri] with downsampling so the long edge is <= [maxEdge] px,
     * then re-encodes as JPEG. Pure I/O; caller chooses dispatcher.
     */
    private fun downscale(uri: Uri, maxEdge: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        // Final exact scale so the long edge is maxEdge (cheap for 256px).
        val longEdge = maxOf(bmp.width, bmp.height)
        val scaled = if (longEdge > maxEdge) {
            val s = maxEdge.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != bmp) bmp.recycle() }
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled != bmp) scaled.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "ProfilePhotoStore"
        private const val MAX_EDGE_PX = 256
        private const val JPEG_QUALITY = 85
    }
}
