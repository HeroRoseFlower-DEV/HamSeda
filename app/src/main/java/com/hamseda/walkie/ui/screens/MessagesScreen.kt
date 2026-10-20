package com.hamseda.walkie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.vm.MessagesViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory
import com.hamseda.walkie.util.toLocalizedDigits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Conversations list, faithful to Telegram's DialogCell: 70dp rows, 56dp
 * avatars, 17sp names, 16sp previews, 12sp dates, blue unread badges,
 * hairline dividers with 72dp start inset. Fully RTL, dark-mode aware.
 */
@Composable
fun MessagesScreen(
    deps: VmDeps,
    dark: Boolean,
    onOpenChat: (peerId: String) -> Unit,
    onOpenDiscovery: () -> Unit,
    vm: MessagesViewModel = viewModel(factory = VmFactory(deps)),
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val livePeerId by vm.livePeerId.collectAsStateWithLifecycle()
    val livePeerName by vm.livePeerName.collectAsStateWithLifecycle()
    val knownPeerIds = remember(conversations) { conversations.map { it.peerId }.toSet() }
    val showNewChat = online && livePeerId.isNotEmpty() && livePeerId !in knownPeerIds
    // Resolved theme passed from MainActivity (honors the user's ThemeMode
    // override) — not isSystemInDarkTheme().
    val headerBg = if (dark) TelegramColors.InputBarDark else Color.White
    val screenBg = if (dark) TelegramColors.ChatBackgroundDark else Color.White

    Scaffold(
        topBar = {
            Surface(color = headerBg, shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.msg_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dark) TelegramColors.HeaderTitleDark
                        else TelegramColors.HeaderTitleLight,
                        modifier = Modifier.weight(1f),
                    )
                    TelegramOnlineDot(online = online, dark = dark)
                }
            }
        },
        containerColor = screenBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!online && conversations.isEmpty()) {
                TelegramOfflineBanner(onOpenDiscovery = onOpenDiscovery, dark = dark)
            }
            if (showNewChat) {
                // Remember the Flow per peer: observePeerProfile builds a
                // fresh combine() each call, and re-collecting it on every
                // recomposition would restart the collection (avatar/name
                // flicker) and re-query Room — same pattern as the rows below.
                val liveProfileFlow = remember(livePeerId) {
                    deps.messaging.observePeerProfile(livePeerId)
                }
                val liveProfile by liveProfileFlow
                    .collectAsStateWithLifecycle(initialValue = null)
                TelegramNewChatCard(
                    peerName = liveProfile?.displayName?.takeIf { it.isNotEmpty() } ?: livePeerName,
                    photoPath = liveProfile?.photoPath,
                    onClick = { onOpenChat(livePeerId) },
                    dark = dark,
                )
            }
            if (conversations.isEmpty() && !showNewChat) {
                TelegramEmptyConversations(dark = dark)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.peerId }) { conv ->
                        // Remember the Flow per peer: observePeerProfile builds a
                        // fresh combine() each call, and re-collecting it on every
                        // recomposition would re-subscribe (and re-query Room).
                        val profileFlow = remember(conv.peerId) {
                            deps.messaging.observePeerProfile(conv.peerId)
                        }
                        val profile by profileFlow
                            .collectAsStateWithLifecycle(initialValue = null)
                        TelegramConversationRow(
                            conv = conv,
                            profile = profile,
                            onClick = { onOpenChat(conv.peerId) },
                            dark = dark,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            thickness = 0.5.dp,
                            color = if (dark) TelegramColors.DividerDark
                            else TelegramColors.DividerLight,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramOnlineDot(online: Boolean, dark: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (online) Color(0xFF4CC38A)
                    else (if (dark) TelegramColors.DialogMessageDark
                    else TelegramColors.DialogMessageLight).copy(alpha = 0.5f),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (online) R.string.msg_online else R.string.msg_offline),
            style = MaterialTheme.typography.labelLarge,
            color = if (dark) TelegramColors.DialogMessageDark
            else TelegramColors.DialogMessageLight,
        )
    }
}

@Composable
private fun TelegramOfflineBanner(onOpenDiscovery: () -> Unit, dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDiscovery)
            .background(if (dark) TelegramColors.DatePillBgDark else TelegramColors.DatePillBgLight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dark) TelegramColors.DatePillTextDark else TelegramColors.DatePillTextLight,
        )
    }
}

@Composable
private fun TelegramEmptyConversations(dark: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(TelegramColors.SendButton.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = TelegramColors.SendButton,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.msg_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (dark) TelegramColors.HeaderTitleDark else TelegramColors.HeaderTitleLight,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.msg_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dark) TelegramColors.DialogMessageDark
            else TelegramColors.DialogMessageLight,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun TelegramNewChatCard(
    peerName: String,
    photoPath: String?,
    onClick: () -> Unit,
    dark: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(TelegramColors.SendButton.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name = peerName, photoPath = photoPath, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.msg_new_conversation),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TelegramColors.SendButton,
            )
            Text(
                text = peerName,
                fontSize = 16.sp,
                color = if (dark) TelegramColors.DialogMessageDark
                else TelegramColors.DialogMessageLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Telegram DialogCell: 70dp row, 56dp avatar, 17sp name, 16sp preview. */
@Composable
private fun TelegramConversationRow(
    conv: MessagingEngine.ConversationView,
    profile: MessagingEngine.PeerProfile?,
    onClick: () -> Unit,
    dark: Boolean,
) {
    val name = profile?.displayName?.takeIf { it.isNotEmpty() } ?: conv.peerName
    val nameColor = if (dark) TelegramColors.DialogNameDark else TelegramColors.DialogNameLight
    val previewColor = if (dark) TelegramColors.DialogMessageDark else TelegramColors.DialogMessageLight
    val dateColor = if (conv.unread > 0) TelegramColors.SendButton
    else if (dark) TelegramColors.DialogDateDark else TelegramColors.DialogDateLight
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(70.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name = name, photoPath = profile?.photoPath, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatListTime(conv.lastTimestamp),
                    color = dateColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conv.lastBody,
                    color = previewColor,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (dark) TelegramColors.UnreadBadgeDark
                                else TelegramColors.UnreadBadgeLight,
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = conv.unread.toString().toLocalizedDigits(
                                androidx.compose.ui.text.intl.Locale.current.platformLocale,
                            ),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** Short relative time for the conversation list; digits follow the UI locale. */
@Composable
private fun formatListTime(ts: Long): String {
    val yesterdayLabel = stringResource(R.string.msg_yesterday)
    // Compose-observable locale (lint NonObservableLocale forbids
    // java.util.Locale.getDefault() here).
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val timeFmt = SimpleDateFormat("HH:mm", locale)
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            timeFmt.format(Date(ts)).toLocalizedDigits(locale)
        run {
            val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            y.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                y.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        } -> yesterdayLabel
        else -> SimpleDateFormat("d MMM", locale).format(Date(ts)).toLocalizedDigits(locale)
    }
}
