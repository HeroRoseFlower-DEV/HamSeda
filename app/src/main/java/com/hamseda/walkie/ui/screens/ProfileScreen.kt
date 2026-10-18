package com.hamseda.walkie.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.proto.Protocol
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.vm.ProfileViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory
import com.hamseda.walkie.util.takeUtf8Bytes
import kotlinx.coroutines.delay

/**
 * User profile: display name + photo. Both are exchanged with the peer
 * over the sealed control channel when a session comes up — never in
 * plaintext, never uploaded anywhere.
 */
@Composable
fun ProfileScreen(
    deps: VmDeps,
    onBack: () -> Unit,
    vm: ProfileViewModel = viewModel(factory = VmFactory(deps)),
) {
    val savedName by vm.profileName.collectAsStateWithLifecycle()
    val photoPath by vm.photoPath.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val headerBg = if (dark) TelegramColors.InputBarDark else Color.White

    var name by remember { mutableStateOf<String?>(null) }
    var savedFlash by remember { mutableStateOf(false) }

    // Seed the editor from the persisted name once it loads.
    LaunchedEffect(savedName) {
        if (name == null) name = savedName
    }
    LaunchedEffect(savedFlash) {
        if (savedFlash) {
            delay(1800)
            savedFlash = false
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) vm.setPhoto(uri) }

    Scaffold(
        topBar = {
            Surface(color = headerBg, shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = if (dark) Color.White else Color(0xFF222222),
                        )
                    }
                    Text(
                        text = stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (dark) Color.White else Color(0xFF222222),
                    )
                }
            }
        },
        containerColor = if (dark) TelegramColors.ChatBackgroundDark else Color.White,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // Avatar with camera badge.
            Box(contentAlignment = Alignment.BottomEnd) {
                val currentName = name ?: savedName
                if (photoPath != null) {
                    PeerAvatar(name = currentName, photoPath = photoPath, size = 110.dp)
                } else {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(TelegramColors.SendButton.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = TelegramColors.SendButton,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        pickPhoto.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(TelegramColors.SendButton),
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = stringResource(R.string.profile_pick_photo),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (photoPath != null) {
                OutlinedButton(onClick = { vm.removePhoto() }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.profile_remove_photo))
                }
            }

            // Display name editor.
            TextField(
                value = name ?: "",
                // Byte-based cap: the wire limit is 64 UTF-8 bytes, and a
                // 64-char Persian name is 128 bytes.
                onValueChange = { name = it.takeUtf8Bytes(Protocol.PROFILE_MAX_NAME_BYTES) },
                label = { Text(stringResource(R.string.profile_name_label)) },
                placeholder = { Text(stringResource(R.string.profile_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        vm.setName(name.orEmpty())
                        savedFlash = true
                    },
                ),
            )

            Button(
                onClick = {
                    vm.setName(name.orEmpty())
                    savedFlash = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TelegramColors.SendButton,
                ),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (savedFlash) stringResource(R.string.profile_saved)
                    else stringResource(R.string.profile_save),
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(R.string.profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = if (dark) TelegramColors.DialogMessageDark else TelegramColors.DialogMessageLight,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
