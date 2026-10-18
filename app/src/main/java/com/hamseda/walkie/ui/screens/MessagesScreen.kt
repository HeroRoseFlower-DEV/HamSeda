package com.hamseda.walkie.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.vm.MessagesViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory
import com.hamseda.walkie.util.toFaDigits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Conversations list, Telegram-style: circular avatars, name + time rows,
 * last-message previews, blue unread badges, hairline dividers.
 * Fully RTL, dark-mode aware.
 */
@Composable
fun MessagesScreen(
    deps: VmDeps,
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
    val dark = isSystemInDarkTheme()
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
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (dark) Color.White else Color(0xFF222222),
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
                val liveProfile by deps.messaging.observePeerProfile(livePeerId)
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
                        val profile by deps.messaging.observePeerProfile(conv.peerId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        TelegramConversationRow(
                            conv = conv,
                            profile = profile,
                            onClick = { onOpenChat(conv.peerId) },
                            dark = dark,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 82.dp),
                            thickness = 0.5.dp,
                            color = if (dark) Color(0xFF242F3D) else Color(0xFFE6E9ED),
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
                    else (if (dark) TelegramColors.TimeInBubbleDark
                    else TelegramColors.TimeInBubbleLight).copy(alpha = 0.5f),
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (online) R.string.msg_online else R.string.msg_offline),
            style = MaterialTheme.typography.labelLarge,
            color = if (dark) TelegramColors.TimeInBubbleDark else TelegramColors.TimeInBubbleLight,
        )
    }
}

@Composable
private fun TelegramOfflineBanner(onOpenDiscovery: () -> Unit, dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDiscovery)
            .background(if (dark) TelegramColors.DateDividerBgDark else TelegramColors.DateDividerBgLight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dark) TelegramColors.DateDividerTextDark else TelegramColors.DateDividerTextLight,
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
            color = if (dark) Color.White else Color(0xFF222222),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.msg_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = if (dark) TelegramColors.TimeInBubbleDark else TelegramColors.TimeInBubbleLight,
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
        PeerAvatar(name = peerName, photoPath = photoPath, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.msg_new_conversation),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TelegramColors.SendButton,
            )
            Text(
                text = peerName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dark) TelegramColors.TimeInBubbleDark else TelegramColors.TimeInBubbleLight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TelegramConversationRow(
    conv: MessagingEngine.ConversationView,
    profile: MessagingEngine.PeerProfile?,
    onClick: () -> Unit,
    dark: Boolean,
) {
    val name = profile?.displayName?.takeIf { it.isNotEmpty() } ?: conv.peerName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name = name, photoPath = profile?.photoPath, size = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dark) Color.White else Color(0xFF222222),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatListTime(conv.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conv.unread > 0) TelegramColors.SendButton
                    else if (dark) TelegramColors.TimeInBubbleDark
                    else TelegramColors.TimeInBubbleLight,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (conv.lastIsFromMe) {
                    Text(
                        text = "✓✓ ",
                        fontSize = 13.sp,
                        color = TelegramColors.TickSent,
                    )
                }
                Text(
                    text = conv.lastBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dark) TelegramColors.TimeInBubbleDark else TelegramColors.TimeInBubbleLight,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(TelegramColors.UnreadBadge)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = conv.unread.toString().toFaDigits(),
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

/** Short relative time for the conversation list, Persian digits. */
@Composable
fun formatListTime(ts: Long): String {
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
            timeFmt.format(Date(ts)).toFaDigits()
        run {
            val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            y.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                y.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        } -> yesterdayLabel
        else -> SimpleDateFormat("d MMM", locale).format(Date(ts)).toFaDigits()
    }
}
