package com.hamseda.walkie.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.vm.ChatViewModel
import com.hamseda.walkie.ui.vm.ChatVmFactory
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.util.toFaDigits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One chat thread, Telegram-style: light-green own bubbles with tails,
 * white peer bubbles, in-bubble timestamps, date dividers, header with
 * avatar + name + status. Fully RTL, dark-mode aware.
 */
@Composable
fun ChatScreen(
    deps: VmDeps,
    peerId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel(factory = ChatVmFactory(deps, peerId)),
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peerTyping by vm.peerTyping.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val peerProfile by vm.peerProfile.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val listState = rememberLazyListState()

    val displayName = peerProfile?.displayName?.takeIf { it.isNotEmpty() } ?: peerName

    // New arrivals while open count as read (drives read receipts).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            vm.onMessagesVisible()
            listState.animateScrollToItem(0)
        }
    }

    val chatBg = if (dark) TelegramColors.ChatBackgroundDark else TelegramColors.ChatBackgroundLight
    val headerBg = if (dark) TelegramColors.InputBarDark else Color.White

    Scaffold(
        topBar = {
            TelegramChatHeader(
                name = displayName,
                photoPath = peerProfile?.photoPath,
                status = when {
                    peerTyping -> stringResource(R.string.msg_typing_full)
                    online -> stringResource(R.string.msg_online)
                    else -> lastSeenText(peerProfile?.lastSeen ?: 0L)
                },
                statusAccent = peerTyping || online,
                onBack = onBack,
                dark = dark,
                backgroundColor = headerBg,
            )
        },
        containerColor = chatBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!online) {
                TelegramOfflineStrip(dark = dark)
            }
            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
            val todayLabel = stringResource(R.string.msg_today)
            val yesterdayLabel = stringResource(R.string.msg_yesterday)
            val chatItems = remember(messages, todayLabel, yesterdayLabel, locale) {
                withDateDividers(messages, todayLabel, yesterdayLabel, locale)
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(chatItems, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Divider -> DateDivider(label = item.label, dark = dark)
                        is ChatItem.Msg -> TelegramBubble(msg = item.msg, dark = dark)
                    }
                }
            }
            TelegramInput(
                onSend = { vm.send(it); vm.onTyping(false) },
                onTyping = { vm.onTyping(it) },
                dark = dark,
            )
        }
    }
}

@Composable
private fun lastSeenText(lastSeen: Long): String {
    if (lastSeen <= 0L) return stringResource(R.string.msg_offline)
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = lastSeen }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val label = if (sameDay) {
        SimpleDateFormat("HH:mm", locale).format(Date(lastSeen)).toFaDigits()
    } else {
        SimpleDateFormat("d MMM", locale).format(Date(lastSeen)).toFaDigits()
    }
    return stringResource(R.string.profile_last_seen, label)
}

@Composable
private fun TelegramChatHeader(
    name: String,
    photoPath: String?,
    status: String,
    statusAccent: Boolean,
    onBack: () -> Unit,
    dark: Boolean,
    backgroundColor: Color,
) {
    Surface(
        color = backgroundColor,
        shadowElevation = 2.dp,
    ) {
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
            PeerAvatar(name = name, photoPath = photoPath, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (dark) Color.White else Color(0xFF222222),
                    maxLines = 1,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusAccent) TelegramColors.SendButton
                    else if (dark) TelegramColors.TimeInBubbleDark
                    else TelegramColors.TimeInBubbleLight,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TelegramOfflineStrip(dark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (dark) TelegramColors.DateDividerBgDark
                else TelegramColors.DateDividerBgLight,
            )
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) TelegramColors.DateDividerTextDark
            else TelegramColors.DateDividerTextLight,
        )
    }
}

// ------------------------------------------------------------ date dividers

private sealed interface ChatItem {
    val key: String
    data class Divider(val label: String) : ChatItem {
        override val key: String = "div-$label"
    }
    data class Msg(val msg: MessagingEngine.MessageView) : ChatItem {
        override val key: String = "msg-${msg.id}"
    }
}

/** Newest-first message list with day divider items inserted. */
private fun withDateDividers(
    messages: List<MessagingEngine.MessageView>,
    todayLabel: String,
    yesterdayLabel: String,
    locale: Locale,
): List<ChatItem> {
    // messages arrive oldest-first; reverse for the reversed LazyColumn.
    val reversed = messages.reversed()
    val out = ArrayList<ChatItem>(reversed.size + 4)
    var lastDay: String? = null
    for (m in reversed) {
        val day = dayKey(m.timestamp)
        if (day != lastDay) {
            out.add(ChatItem.Divider(dayLabel(m.timestamp, todayLabel, yesterdayLabel, locale)))
            lastDay = day
        }
        out.add(ChatItem.Msg(m))
    }
    return out
}

private fun dayKey(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
}

private fun dayLabel(ts: Long, todayLabel: String, yesterdayLabel: String, locale: Locale): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return todayLabel
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val yesterday = y.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        y.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return yesterdayLabel
    return SimpleDateFormat("d MMMM", locale).format(Date(ts)).toFaDigits()
}

@Composable
private fun DateDivider(label: String, dark: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (dark) TelegramColors.DateDividerBgDark
                    else TelegramColors.DateDividerBgLight,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) TelegramColors.DateDividerTextDark
            else TelegramColors.DateDividerTextLight,
            fontSize = 12.sp,
        )
    }
}

// ------------------------------------------------------------------ bubbles

@Composable
private fun TelegramBubble(
    msg: MessagingEngine.MessageView,
    dark: Boolean,
) {
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val time = remember(msg.timestamp, locale) {
        SimpleDateFormat("HH:mm", locale).format(Date(msg.timestamp)).toFaDigits()
    }
    val isMe = msg.isFromMe
    val bg = when {
        isMe && dark -> TelegramColors.OutgoingBubbleDark
        isMe -> TelegramColors.OutgoingBubbleLight
        dark -> TelegramColors.IncomingBubbleDark
        else -> TelegramColors.IncomingBubbleLight
    }
    val textColor = when {
        isMe && dark -> TelegramColors.OutgoingTextDark
        isMe -> TelegramColors.OutgoingTextLight
        dark -> TelegramColors.IncomingTextDark
        else -> TelegramColors.IncomingTextLight
    }
    // Tail sits on the outer edge: BottomEnd for own, BottomStart for peer
    // (correct in both LTR and RTL).
    val tailAlign = if (isMe) Alignment.BottomEnd else Alignment.BottomStart
    val corners = if (isMe) {
        RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
    } else {
        RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        Box(modifier = Modifier.fillMaxWidth(0.78f)) {
            Box(
                modifier = Modifier
                    .clip(corners)
                    .background(bg)
                    .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 5.dp),
            ) {
                Column {
                    Text(
                        text = msg.body,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 16.sp,
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isMe && (msg.state == MessageState.QUEUED || msg.state == MessageState.FAILED)) {
                            Text(
                                text = stringResource(
                                    if (msg.state == MessageState.QUEUED) R.string.msg_state_queued
                                    else R.string.msg_state_failed,
                                ),
                                fontSize = 10.sp,
                                color = if (msg.state == MessageState.FAILED) MaterialTheme.colorScheme.error
                                else TelegramColors.TimeInBubbleLight,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = time,
                            fontSize = 11.sp,
                            color = if (dark) TelegramColors.TimeInBubbleDark
                            else TelegramColors.TimeInBubbleLight,
                        )
                        if (isMe && msg.state >= MessageState.SENT) {
                            Spacer(Modifier.width(3.dp))
                            TelegramTicks(state = msg.state, dark = dark)
                        }
                    }
                }
            }
            // Tail triangle overlapping the bubble's outer bottom corner.
            Canvas(
                modifier = Modifier
                    .size(9.dp)
                    .align(tailAlign),
            ) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    if (isMe) {
                        // Tail on the outer (end) edge, pointing down-out.
                        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h); close()
                    } else {
                        moveTo(w, 0f); lineTo(0f, 0f); lineTo(0f, h); close()
                    }
                }
                drawPath(path, bg)
            }
        }
    }
}

@Composable
private fun TelegramTicks(state: Int, dark: Boolean) {
    val (glyph, color) = when (state) {
        MessageState.SENT -> "✓" to TelegramColors.TickSent
        MessageState.DELIVERED -> "✓✓" to TelegramColors.TickSent
        MessageState.READ -> "✓✓" to TelegramColors.TickRead
        else -> "" to Color.Transparent
    }
    if (glyph.isNotEmpty()) {
        Text(
            text = glyph,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// -------------------------------------------------------------------- input

@Composable
private fun TelegramInput(
    onSend: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    dark: Boolean,
) {
    var text by remember { mutableStateOf("") }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        onSend(t)
        text = ""
    }

    val barBg = if (dark) TelegramColors.InputBarDark else TelegramColors.InputBarLight

    Surface(color = barBg, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    onTyping(it.isNotBlank())
                },
                placeholder = {
                    Text(
                        stringResource(R.string.msg_input_hint),
                        color = if (dark) TelegramColors.InputHintDark
                        else TelegramColors.InputHintLight,
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = if (dark) TelegramColors.ChatBackgroundDark
                    else TelegramColors.ChatBackgroundLight,
                    unfocusedContainerColor = if (dark) TelegramColors.ChatBackgroundDark
                    else TelegramColors.ChatBackgroundLight,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { submit() },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank()) TelegramColors.SendButton
                        else (if (dark) TelegramColors.DateDividerBgDark
                        else TelegramColors.DateDividerBgLight),
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.msg_send),
                    tint = Color.White,
                )
            }
        }
    }
}
