package com.hamseda.walkie.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hamseda.walkie.R
import com.hamseda.walkie.util.PermissionHelper

private data class PermissionStep(
    val permissions: List<String>,
    val labelRes: Int,
)

/**
 * Requests core permissions automatically on app launch, in clear groups.
 * Android owns the actual system dialogs and can group Nearby Devices
 * permissions (Bluetooth and nearby Wi-Fi) into one prompt.
 */
@Composable
fun StartupPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    var shouldAutoRequest by rememberSaveable { mutableStateOf(true) }
    var requestInFlight by remember { mutableStateOf(false) }
    var deniedByUser by rememberSaveable { mutableStateOf(false) }
    var requestedPermissions by rememberSaveable { mutableStateOf(emptyList<String>()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-evaluate after the runtime dialog or app settings returns.
    @Suppress("UNUSED_VARIABLE")
    val permissionRefresh = refreshKey

    // ADVERTISE is deliberately not requested on launch: it is needed only if
    // the user chooses Bluetooth "wait for incoming connection" mode.
    val bluetoothPermissions =
        PermissionHelper.missingBluetoothPermissions(context, forDiscovery = true)
    val wifiPermissions = PermissionHelper.missingWifiDirectPermissions(context)
    val localNetworkPermissions = PermissionHelper.missingLocalNetworkPermission(context)
    val microphonePermissions = PermissionHelper.missingMicrophonePermission(context)

    // Bluetooth and nearby Wi-Fi permissions belong to Android's Nearby
    // Devices permission group. Request them together so Android can present
    // the correct combined system prompt instead of a misleading partial flow.
    val nearbyDevicePermissions = (bluetoothPermissions + wifiPermissions).distinct()

    val nextStep = when {
        nearbyDevicePermissions.isNotEmpty() -> PermissionStep(
            nearbyDevicePermissions,
            R.string.startup_permissions_nearby,
        )
        localNetworkPermissions.isNotEmpty() -> PermissionStep(
            localNetworkPermissions,
            R.string.startup_permissions_local_network,
        )
        microphonePermissions.isNotEmpty() -> PermissionStep(
            microphonePermissions,
            R.string.startup_permissions_microphone,
        )
        else -> null
    }

    // Process death while the system dialog was open orphans the request:
    // shouldAutoRequest was already consumed (saveable), requestInFlight
    // reset (not saveable), and no denial was recorded — so neither the
    // auto-launch nor the denied UI would appear, leaving the gate idle
    // with no in-app retry. Detect it and offer the retry action instead.
    // (Rotation during the dialog can transiently show the same UI; the
    // still-open dialog's callback settles the state when it returns.)
    val orphanedRequest = nextStep != null &&
        !shouldAutoRequest && !requestInFlight && !deniedByUser

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        requestInFlight = false
        val allRequestedGranted = requestedPermissions.isNotEmpty() &&
            requestedPermissions.all { PermissionHelper.isGranted(context, it) }
        val callbackGranted = results.isNotEmpty() && results.values.all { it }

        if (allRequestedGranted || callbackGranted) {
            // Continue automatically to the next permission group. If Android
            // granted the nearby-device group together, the next step will
            // naturally skip Bluetooth/Wi-Fi permissions already granted.
            deniedByUser = false
            shouldAutoRequest =
                PermissionHelper.missingStartupRuntimePermissions(context).isNotEmpty()
        } else {
            // Do not loop system dialogs after a denial; offer explicit retry
            // and app settings instead.
            deniedByUser = true
            shouldAutoRequest = false
        }
        requestedPermissions = emptyList()
        refreshKey++
    }

    LaunchedEffect(shouldAutoRequest, requestInFlight, nextStep, refreshKey) {
        if (shouldAutoRequest && !requestInFlight && nextStep != null) {
            requestedPermissions = nextStep.permissions
            shouldAutoRequest = false
            requestInFlight = true
            permissionLauncher.launch(nextStep.permissions.toTypedArray())
        }
    }

    if (nextStep == null) {
        content()
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.startup_permissions_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.startup_permissions_message),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.startup_permissions_details),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(nextStep.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (requestInFlight) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.startup_permissions_requesting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (deniedByUser || orphanedRequest) {
                Text(
                    text = stringResource(
                        if (deniedByUser) R.string.startup_permissions_denied
                        else R.string.startup_permissions_interrupted,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.startup_permissions_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        deniedByUser = false
                        shouldAutoRequest = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.startup_permissions_continue))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.startup_permissions_open_settings))
            }
        }
    }
}
