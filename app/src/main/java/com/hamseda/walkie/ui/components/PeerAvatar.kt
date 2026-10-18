package com.hamseda.walkie.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Circular peer avatar. Shows the profile photo when [photoPath] points at
 * an existing file; otherwise a Telegram-style initial with a
 * deterministic background color derived from [name].
 */
@Composable
fun PeerAvatar(
    name: String,
    photoPath: String?,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(photoPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photoPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val f = photoPath?.let { File(it) } ?: return@runCatching null
                if (!f.exists() || f.length() == 0L) return@runCatching null
                // Downsample: an avatar never needs more than ~2x its dp size.
                val targetPx = 256
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > targetPx || bounds.outHeight / sample > targetPx) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "؟"
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarColor(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42).sp,
            )
        }
    }
}

/** Telegram-style deterministic avatar colors. */
private fun avatarColor(name: String): Color {
    val palette = listOf(
        Color(0xFFE17076), // red
        Color(0xFF7BC862), // green
        Color(0xFFF5A623), // orange
        Color(0xFF5AA9E6), // blue
        Color(0xFF9B7EDE), // purple
        Color(0xFF4FC3C3), // teal
        Color(0xFFF27BB5), // pink
    )
    val idx = (name.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}
