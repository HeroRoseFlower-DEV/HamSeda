package com.hamseda.walkie.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.BuildConfig
import com.hamseda.walkie.R
import com.hamseda.walkie.data.SettingsRepository
import com.hamseda.walkie.proto.CodecId
import com.hamseda.walkie.transport.TransportPreference
import com.hamseda.walkie.ui.vm.SettingsViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory

@Composable
fun SettingsScreen(
    deps: VmDeps,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onLanguageChanged: () -> Unit,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(factory = VmFactory(deps)),
) {
    val transportPref by vm.transportPreference.collectAsStateWithLifecycle()
    val codecPref by vm.codecPref.collectAsStateWithLifecycle()
    val speakerphone by vm.speakerphone.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingSection(title = stringResource(R.string.settings_transport)) {
                RadioOption(
                    selected = transportPref == TransportPreference.AUTO_RECOMMEND,
                    title = stringResource(R.string.transport_auto),
                    onClick = { vm.setTransportPreference(TransportPreference.AUTO_RECOMMEND) },
                )
                RadioOption(
                    selected = transportPref == TransportPreference.WIFI_DIRECT,
                    title = stringResource(R.string.transport_wifi_direct),
                    onClick = { vm.setTransportPreference(TransportPreference.WIFI_DIRECT) },
                )
                RadioOption(
                    selected = transportPref == TransportPreference.BLUETOOTH,
                    title = stringResource(R.string.transport_bluetooth),
                    onClick = { vm.setTransportPreference(TransportPreference.BLUETOOTH) },
                )
            }

            SettingSection(title = stringResource(R.string.settings_codec)) {
                RadioOption(
                    selected = codecPref == CodecId.OPUS,
                    title = stringResource(R.string.codec_opus),
                    subtitle = stringResource(R.string.codec_opus_desc),
                    onClick = { vm.setCodec(CodecId.OPUS) },
                )
                RadioOption(
                    selected = codecPref == CodecId.PCM16,
                    title = stringResource(R.string.codec_pcm),
                    subtitle = stringResource(R.string.codec_pcm_desc),
                    onClick = { vm.setCodec(CodecId.PCM16) },
                )
            }

            SettingSection(title = "") {
                SwitchRow(
                    title = stringResource(R.string.settings_speakerphone),
                    checked = speakerphone,
                    onChecked = vm::setSpeakerphone,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_vibration),
                    checked = vibration,
                    onChecked = vm::setVibration,
                )
            }

            SettingSection(title = stringResource(R.string.settings_theme)) {
                RadioOption(
                    selected = theme == SettingsRepository.ThemeMode.SYSTEM,
                    title = stringResource(R.string.theme_system),
                    onClick = { vm.setTheme(SettingsRepository.ThemeMode.SYSTEM) },
                )
                RadioOption(
                    selected = theme == SettingsRepository.ThemeMode.LIGHT,
                    title = stringResource(R.string.theme_light),
                    onClick = { vm.setTheme(SettingsRepository.ThemeMode.LIGHT) },
                )
                RadioOption(
                    selected = theme == SettingsRepository.ThemeMode.DARK,
                    title = stringResource(R.string.theme_dark),
                    onClick = { vm.setTheme(SettingsRepository.ThemeMode.DARK) },
                )
            }

            SettingSection(title = stringResource(R.string.settings_language)) {
                RadioOption(
                    selected = language.isEmpty(),
                    title = stringResource(R.string.lang_system),
                    onClick = { vm.setLanguage("", onLanguageChanged) },
                )
                RadioOption(
                    selected = language == "fa",
                    title = stringResource(R.string.lang_fa),
                    onClick = { vm.setLanguage("fa", onLanguageChanged) },
                )
                RadioOption(
                    selected = language == "en",
                    title = stringResource(R.string.lang_en),
                    onClick = { vm.setLanguage("en", onLanguageChanged) },
                )
            }

            SettingSection(title = "") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPrivacy)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_privacy),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.version_label)} ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun RadioOption(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
