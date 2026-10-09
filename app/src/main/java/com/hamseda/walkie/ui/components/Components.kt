package com.hamseda.walkie.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamseda.walkie.R
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.transport.PeerDevice
import com.hamseda.walkie.transport.TransportType
import com.hamseda.walkie.ui.theme.HamSedaColors

/** Connection status pill shown at the top of Home. */
@Composable
fun StatusHeader(
    phase: SessionManager.Phase,
    transportType: TransportType?,
    peerName: String,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (phase) {
        SessionManager.Phase.IDLE -> stringResource(R.string.status_idle) to HamSedaColors.NavySoft
        SessionManager.Phase.CONNECTING_TRANSPORT -> stringResource(R.string.status_connecting) to HamSedaColors.WarmAlert
        SessionManager.Phase.HANDSHAKE -> stringResource(R.string.status_handshake) to HamSedaColors.WarmAlert
        SessionManager.Phase.AWAITING_SAS_CONFIRM -> stringResource(R.string.status_verifying) to HamSedaColors.WarmAlert
        SessionManager.Phase.IN_SESSION -> stringResource(R.string.status_in_session) to HamSedaColors.SuccessGreen
        SessionManager.Phase.ENDED -> stringResource(R.string.status_idle) to HamSedaColors.NavySoft
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_connection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val transportLabel = when (transportType) {
                    TransportType.WIFI_DIRECT -> stringResource(R.string.transport_wifi_direct)
                    TransportType.BLUETOOTH -> stringResource(R.string.transport_bluetooth)
                    null -> null
                }
                if (transportLabel != null) {
                    Text(
                        text = transportLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (phase == SessionManager.Phase.IN_SESSION && peerName.isNotBlank()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.connected_to),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/**
 * The large push-to-talk control. Press-and-hold with immediate release;
 * haptic tick on press; scales + glows while transmitting. [level] is the
 * real measured microphone amplitude (0..1).
 */
@Composable
fun PttButton(
    transmitting: Boolean,
    enabled: Boolean,
    level: Float,
    onPressDown: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (transmitting) 1.08f else 1f,
        label = "ptt-scale",
    )
    val glowAlpha = if (transmitting) 0.25f + 0.35f * level else 0f
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Real-time transmit glow driven by the measured voice level.
        Box(
            modifier = Modifier
                .size(196.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha)),
        )
        Surface(
            modifier = Modifier
                .size(168.dp)
                .scale(scale)
                .semantics {
                    contentDescription = if (transmitting) "transmitting" else "hold to talk"
                }
                .pointerInput(enabled) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled) return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPressDown()
                            tryAwaitRelease()
                            onRelease()
                        },
                    )
                },
            shape = CircleShape,
            color = if (transmitting) HamSedaColors.WarmAlert
            else MaterialTheme.colorScheme.primary,
            shadowElevation = if (transmitting) 12.dp else 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (transmitting) R.string.ptt_transmitting else R.string.ptt_label,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Live waveform bars driven by the real voice [level] (0..1). */
@Composable
fun VoiceLevelBars(
    level: Float,
    transmitting: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    maxBarHeight: Dp = 44.dp,
) {
    Row(
        modifier = modifier.height(maxBarHeight),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until barCount) {
            // Deterministic pseudo-variation per bar so the meter looks alive
            // while still being driven by the real measured level.
            val variation = 0.55f + 0.45f * ((i * 37 % 11) / 10f)
            val h = if (transmitting) {
                (0.12f + level * variation).coerceIn(0.08f, 1f)
            } else {
                0.08f
            }
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(maxBarHeight * h)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (transmitting) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}

/** One discovered peer row. The name is display-only (never identity). */
@Composable
fun PeerCard(
    peer: PeerDevice,
    connecting: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (peer.transport) {
                        TransportType.WIFI_DIRECT -> stringResource(R.string.transport_wifi_direct)
                        TransportType.BLUETOOTH -> stringResource(R.string.transport_bluetooth)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onConnect, enabled = !connecting) {
                Text(
                    text = stringResource(R.string.peer_connect),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Dismissible error banner reflecting a real session/transport error. */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    }
}

/** Small status line with an icon (floor state, notices). */
@Composable
fun StatusLine(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val (icon, color) = when (tone) {
        StatusTone.OK -> Icons.Filled.CheckCircle to HamSedaColors.SuccessGreen
        StatusTone.WARN -> Icons.Filled.Warning to HamSedaColors.WarmAlert
        StatusTone.INFO -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
        )
    }
}

enum class StatusTone { OK, WARN, INFO }
