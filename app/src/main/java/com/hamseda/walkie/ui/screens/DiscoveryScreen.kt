@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hamseda.walkie.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.session.SessionManager
import com.hamseda.walkie.transport.Availability
import com.hamseda.walkie.transport.TransportPreference
import com.hamseda.walkie.transport.TransportState
import com.hamseda.walkie.transport.TransportType
import com.hamseda.walkie.ui.components.ErrorBanner
import com.hamseda.walkie.ui.components.PeerCard
import com.hamseda.walkie.ui.vm.DiscoveryViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory
import com.hamseda.walkie.util.PermissionHelper

@Composable
fun DiscoveryScreen(
    deps: VmDeps,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: DiscoveryViewModel = viewModel(factory = VmFactory(deps)),
) {
    val preference by vm.preference.collectAsStateWithLifecycle()
    val activeType by vm.activeType.collectAsStateWithLifecycle()
    val choiceExplanation by vm.choiceExplanation.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val transportState by vm.transportState.collectAsStateWithLifecycle()
    val transportError by vm.transportError.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val discoverable by vm.discoverable.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-run whatever the user tried to do; transports re-check.
        pendingAction?.invoke()
        pendingAction = null
        vm.refreshAvailability()
    }
    // System dialog that makes this phone visible to Bluetooth scans.
    // Needs BLUETOOTH_ADVERTISE on API 31+ (requested via the permission
    // flow before listen/scan).
    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }

    fun requestDiscoverable(then: () -> Unit) {
        val intent = android.content.Intent(
            android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE,
        ).apply {
            putExtra(
                android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                300, // max the platform honors
            )
        }
        pendingAction = then
        discoverableLauncher.launch(intent)
    }

    fun missingForScan(): List<String> = if (activeType == TransportType.WIFI_DIRECT) {
        PermissionHelper.missingWifiDirectPermissions(context)
    } else {
        PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
    }

    /** Connect-time permissions: discovery + local-network access on API 37+. */
    fun missingForConnect(): List<String> = if (activeType == TransportType.WIFI_DIRECT) {
        PermissionHelper.missingWifiDirectConnectPermissions(context)
    } else {
        PermissionHelper.missingBluetoothPermissions(context, forDiscovery = false)
    }

    fun doScan() {
        val missing = missingForScan()
        if (missing.isNotEmpty()) {
            pendingAction = { vm.startScan() }
            permLauncher.launch(missing.toTypedArray())
        } else {
            vm.startScan()
        }
    }

    fun doConnectPeer(peer: com.hamseda.walkie.transport.PeerDevice) {
        val missing = missingForConnect()
        if (missing.isNotEmpty()) {
            pendingAction = { vm.connectPeer(peer) }
            permLauncher.launch(missing.toTypedArray())
        } else {
            vm.connectPeer(peer)
        }
    }

    fun doListen() {
        val missing = missingForConnect()
        if (missing.isNotEmpty()) {
            pendingAction = { vm.listenForIncoming() }
            permLauncher.launch(missing.toTypedArray())
            return
        }
        // Bluetooth classic only finds *discoverable* phones: make this
        // phone visible first, then open the RFCOMM server socket.
        if (activeType == TransportType.BLUETOOTH && !discoverable) {
            requestDiscoverable { vm.listenForIncoming() }
        } else {
            vm.listenForIncoming()
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopScan() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discovery_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.mode_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
            }
            item {
                ModeSelector(
                    preference = preference,
                    onSelect = vm::setPreference,
                )
            }
            if (choiceExplanation.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(choiceExplanationRes(choiceExplanation)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                AvailabilityCard(
                    availability = availability,
                    activeType = activeType,
                    onRetry = { vm.refreshAvailability() },
                )
            }
            // Bluetooth classic discovery only finds *discoverable* phones,
            // and connecting needs the peer to be *listening* too. The card
            // button runs the full flow (make visible → listen), same as
            // the Listen button below.
            if (activeType == TransportType.BLUETOOTH) {
                item {
                    DiscoverabilityCard(
                        discoverable = discoverable,
                        listening = phase != SessionManager.Phase.IDLE,
                        onMakeVisibleAndListen = { doListen() },
                    )
                }
            }
            // Permission rationale (shown before the system dialog when needed).
            // On API 37+, Wi-Fi Direct also needs local-network access to open
            // P2P sockets — surfaced here before connect/listen. Note: this
            // block runs in LazyListScope (not @Composable), so resolve the
            // string inside item {} below.
            val missingScan = missingForScan()
            val missingLocalNet =
                PermissionHelper.missingLocalNetworkPermission(context)
            val rationaleRes: Int? = when {
                missingLocalNet.isNotEmpty() && activeType == TransportType.WIFI_DIRECT ->
                    R.string.perm_localnet_message
                missingScan.isNotEmpty() && activeType == TransportType.WIFI_DIRECT ->
                    R.string.perm_wifi_message
                missingScan.isNotEmpty() ->
                    R.string.perm_bt_message
                else -> null
            }
            if (rationaleRes != null) {
                item {
                    PermissionRationaleCard(
                        message = stringResource(rationaleRes),
                        onGrant = {
                            pendingAction = { vm.startScan() }
                            permLauncher.launch(
                                (missingScan + missingLocalNet).distinct().toTypedArray(),
                            )
                        },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val scanning = transportState == TransportState.DISCOVERING
                    Button(
                        onClick = { if (scanning) vm.stopScan() else doScan() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (scanning) CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (scanning) R.string.btn_stop_scan else R.string.btn_scan,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = { doListen() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        enabled = phase == SessionManager.Phase.IDLE,
                    ) {
                        Text(stringResource(R.string.listening_title), fontWeight = FontWeight.Bold)
                    }
                }
                if (phase != SessionManager.Phase.IDLE) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.listening_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            transportError?.let { e ->
                item {
                    ErrorBanner(
                        message = transportErrorMessage(e),
                        onDismiss = { vm.clearTransportError() },
                    )
                }
            }
            if (peers.isEmpty()) {
                item {
                    EmptyPeers()
                }
            } else {
                items(peers, key = { it.id }) { peer ->
                    PeerCard(
                        peer = peer,
                        connecting = transportState == TransportState.CONNECTING,
                        onConnect = { doConnectPeer(peer) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    preference: TransportPreference,
    onSelect: (TransportPreference) -> Unit,
) {
    val options = listOf(
        Triple(TransportPreference.AUTO_RECOMMEND, R.string.transport_auto, Icons.Filled.Radar),
        Triple(TransportPreference.WIFI_DIRECT, R.string.transport_wifi_direct, Icons.Filled.Wifi),
        Triple(TransportPreference.BLUETOOTH, R.string.transport_bluetooth, Icons.Filled.Bluetooth),
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            options.forEach { (pref, label, icon) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = preference == pref,
                            onClick = { onSelect(pref) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = preference == pref, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (preference == pref) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailabilityCard(
    availability: Availability?,
    activeType: TransportType,
    onRetry: () -> Unit,
) {
    if (availability == null) return
    if (availability.supported && availability.enabled) return
    val message = when {
        !availability.supported -> stringResource(R.string.err_unsupported)
        activeType == TransportType.WIFI_DIRECT -> stringResource(R.string.err_radio_disabled_wifi)
        else -> stringResource(R.string.err_radio_disabled_bt)
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun PermissionRationaleCard(message: String, onGrant: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.perm_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGrant, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.perm_grant), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyPeers() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Radar,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.discovery_no_peers),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.discovery_no_peers_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun choiceExplanationRes(key: String): Int = when (key) {
    "auto_wifi_direct" -> R.string.choice_explain_auto_wifi
    "auto_bluetooth" -> R.string.choice_explain_auto_bt
    "manual_wifi_direct" -> R.string.choice_explain_manual_wifi
    else -> R.string.choice_explain_manual_bt
}

@Composable
private fun transportErrorMessage(e: com.hamseda.walkie.transport.TransportError): String =
    when (e) {
        is com.hamseda.walkie.transport.TransportError.PermissionDenied ->
            stringResource(R.string.perm_denied_hint)
        is com.hamseda.walkie.transport.TransportError.RadioDisabled ->
            stringResource(
                if (e.transport == TransportType.WIFI_DIRECT) R.string.err_radio_disabled_wifi
                else R.string.err_radio_disabled_bt,
            )
        is com.hamseda.walkie.transport.TransportError.ConnectFailed ->
            stringResource(R.string.err_connect_failed)
        is com.hamseda.walkie.transport.TransportError.ConnectionLost ->
            stringResource(R.string.err_transport_lost)
        is com.hamseda.walkie.transport.TransportError.PeerNotFound ->
            stringResource(R.string.err_peer_not_found)
        is com.hamseda.walkie.transport.TransportError.HandshakeTimeout ->
            stringResource(R.string.err_handshake_timeout)
        is com.hamseda.walkie.transport.TransportError.Unsupported ->
            stringResource(R.string.err_unsupported)
    }

@Composable
private fun DiscoverabilityCard(
    discoverable: Boolean,
    listening: Boolean,
    onMakeVisibleAndListen: () -> Unit,
) {
    val ready = discoverable && listening
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (ready) R.string.bt_ready_title
                        else R.string.bt_not_ready_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bt_not_ready_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ready) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onMakeVisibleAndListen,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.bt_make_visible_and_listen))
                }
            }
        }
    }
}
