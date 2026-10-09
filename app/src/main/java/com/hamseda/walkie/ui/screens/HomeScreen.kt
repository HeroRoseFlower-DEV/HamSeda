@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hamseda.walkie.ui.screens

import android.Manifest
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.session.FloorController
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.ui.components.ErrorBanner
import com.hamseda.walkie.ui.components.PttButton
import com.hamseda.walkie.ui.components.StatusHeader
import com.hamseda.walkie.ui.components.StatusLine
import com.hamseda.walkie.ui.components.StatusTone
import com.hamseda.walkie.ui.components.VoiceLevelBars
import com.hamseda.walkie.ui.vm.HomeViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory

@Composable
fun HomeScreen(
    deps: VmDeps,
    onOpenDiscovery: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = viewModel(factory = VmFactory(deps)),
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    val transportType by vm.transportType.collectAsStateWithLifecycle()
    val transmitting by vm.isTransmitting.collectAsStateWithLifecycle()
    val floorHolder by vm.floorHolder.collectAsStateWithLifecycle()
    val level by vm.voiceLevel.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val vibrationEnabled by deps.settings.vibrationFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val vibrator = rememberVibrator()
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.setPtt(true)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            StatusHeader(phase = phase, transportType = transportType, peerName = peerName)

            Spacer(Modifier.height(16.dp))
            error?.let { e ->
                ErrorBanner(
                    message = stringResource(errorStringRes(e)),
                    onDismiss = vm::clearError,
                )
                Spacer(Modifier.height(12.dp))
            }

            val inSession = phase == SessionManager.Phase.IN_SESSION
            if (!inSession) {
                EmptyPeerCard(onOpenDiscovery)
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.ptt_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                // Floor status — never color-only: icon + text.
                when (floorHolder) {
                    FloorController.Holder.SELF -> StatusLine(
                        stringResource(R.string.floor_you), StatusTone.OK,
                    )
                    FloorController.Holder.PEER -> StatusLine(
                        stringResource(R.string.floor_peer), StatusTone.WARN,
                    )
                    FloorController.Holder.NONE -> StatusLine(
                        stringResource(R.string.floor_free), StatusTone.INFO,
                    )
                }
                Spacer(Modifier.height(12.dp))
                VoiceLevelBars(level = level, transmitting = transmitting || floorHolder == FloorController.Holder.PEER)
                if (floorHolder == FloorController.Holder.PEER) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.receiving_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                PttButton(
                    transmitting = transmitting,
                    enabled = true,
                    level = level,
                    onPressDown = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            if (vibrationEnabled) {
                                vibrator?.vibrate(
                                    VibrationEffect.createOneShot(
                                        40, VibrationEffect.DEFAULT_AMPLITUDE,
                                    ),
                                )
                            }
                            vm.setPtt(true)
                        } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onRelease = { vm.setPtt(false) },
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = vm::endSession,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.PhoneDisabled, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.btn_end_session))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberVibrator(): Vibrator? {
    val context = LocalContext.current
    return remember {
        context.getSystemService(Vibrator::class.java)
    }
}

@Composable
private fun EmptyPeerCard(onOpenDiscovery: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Radar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.no_peer_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.no_peer_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenDiscovery,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.btn_find_peer), fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun errorStringRes(e: SessionManager.SessionError): Int = when (e) {
    SessionManager.SessionError.AUTH_MISMATCH -> R.string.err_auth_mismatch
    SessionManager.SessionError.PEER_BUSY -> R.string.err_peer_busy
    SessionManager.SessionError.TRANSPORT_LOST -> R.string.err_transport_lost
    SessionManager.SessionError.PEER_TIMEOUT -> R.string.err_peer_timeout
    SessionManager.SessionError.PROTOCOL_ERROR -> R.string.err_protocol
    SessionManager.SessionError.MIC_UNAVAILABLE -> R.string.err_mic
    SessionManager.SessionError.PEER_REJECTED -> R.string.err_peer_rejected
}
