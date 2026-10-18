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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.HamSedaTopBar
import com.hamseda.walkie.ui.theme.HamSedaColors
import com.hamseda.walkie.ui.theme.MessagingColors
import com.hamseda.walkie.ui.vm.MessagesViewModel
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.ui.vm.VmFactory
import com.hamseda.walkie.util.toFaDigits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Conversations list — the messaging home. Deliberately its own visual
 * world: violet accents, gradient avatars, soft cards; nothing teal here.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            HamSedaTopBar(
                title = stringResource(R.string.msg_title),
                actions = {
                    OnlineDot(online = online)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!online && conversations.isEmpty()) {
                OfflineBanner(onOpenDiscovery = onOpenDiscovery)
            }
            if (showNewChat) {
                NewChatCard(
                    peerName = livePeerName,
                    onClick = { onOpenChat(livePeerId) },
                )
            }
            if (conversations.isEmpty() && !showNewChat) {
                EmptyConversations()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(conversations, key = { it.peerId }) { conv ->
                        ConversationRow(
                            conv = conv,
                            onClick = { onOpenChat(conv.peerId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    val color = if (online) {
        HamSedaColors.SuccessGreen
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(if (online) R.string.msg_online else R.string.msg_offline),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OfflineBanner(onOpenDiscovery: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpenDiscovery),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MessagingColors.VioletSoft.copy(alpha = 0.55f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MessagingColors.VioletDeep,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EmptyConversations() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MessagingColors.VioletPrimary.copy(alpha = 0.16f),
                            MessagingColors.VioletDeep.copy(alpha = 0.06f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MessagingColors.VioletPrimary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.msg_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.msg_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun NewChatCard(peerName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MessagingColors.VioletPrimary.copy(alpha = 0.12f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(name = peerName, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msg_new_conversation),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MessagingColors.VioletPrimary,
                )
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: MessagingEngine.ConversationView,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name = conv.peerName)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conv.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatListTime(conv.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conv.unread > 0) MessagingColors.VioletPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (conv.unread > 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conv.lastBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MessagingColors.VioletPrimary) {
                        Text(
                            conv.unread.toString().toFaDigits(),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerAvatar(name: String, size: androidx.compose.ui.unit.Dp = 52.dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "؟"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(MessagingColors.VioletPrimary, MessagingColors.VioletDeep),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MessagingColors.VioletInk,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42).sp,
        )
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
