package com.hamseda.walkie.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.components.TelegramBubble
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
 * One chat thread, faithful to Telegram's Android UI (reverse-engineered
 * geometry from the official source): 17dp bubble corners with 6dp grouped
 * near-corners, curved tails only on the last message of a run, inline
 * in-bubble time + vector ticks, 14sp date pills, exact color tokens.
 * Fully RTL, dark-mode aware.
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(chatItems, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Divider -> {
                            DateDivider(label = item.label, dark = dark)
                            Spacer(Modifier.size(6.dp))
                        }
                        is ChatItem.Msg -> {
                            val msg = item.msg
                            val time = remember(msg.timestamp, locale) {
                                SimpleDateFormat("HH:mm", locale)
                                    .format(Date(msg.timestamp)).toFaDigits()
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (msg.isFromMe) Arrangement.End
                                else Arrangement.Start,
                            ) {
                                Column {
                                    if (msg.isFromMe &&
                                        (msg.state == MessageState.QUEUED || msg.state == MessageState.FAILED)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (msg.state == MessageState.QUEUED) R.string.msg_state_queued
                                                else R.string.msg_state_failed,
                                            ),
                                            fontSize = 11.sp,
                                            color = if (msg.state == MessageState.FAILED) MaterialTheme.colorScheme.error
                                            else if (dark) TelegramColors.InTimeTextDark
                                            else TelegramColors.InTimeTextLight,
                                            modifier = Modifier
                                                .align(Alignment.End)
                                                .padding(end = 12.dp, bottom = 2.dp),
                                        )
                                    }
                                    TelegramBubble(
                                        body = msg.body,
                                        isFromMe = msg.isFromMe,
                                        time = time,
                                        state = msg.state,
                                        isFirstInGroup = item.isFirstInGroup,
                                        isLastInGroup = item.isLastInGroup,
                                        dark = dark,
                                    )
                                }
                            }
                            // Telegram-like grouping gaps: tight inside a run.
                            Spacer(Modifier.size(if (item.isLastInGroup) 8.dp else 2.dp))
                        }
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
                    tint = if (dark) TelegramColors.HeaderTitleDark else Color(0xFF222222),
                )
            }
            PeerAvatar(name = name, photoPath = photoPath, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dark) TelegramColors.HeaderTitleDark else TelegramColors.HeaderTitleLight,
                    maxLines = 1,
                )
                Text(
                    text = status,
                    fontSize = 14.sp,
                    color = when {
                        statusAccent && dark -> TelegramColors.OnlineBlueDark
                        statusAccent -> TelegramColors.OnlineBlueLight
                        dark -> TelegramColors.HeaderSubtitleDark
                        else -> TelegramColors.HeaderSubtitleLight
                    },
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
                if (dark) TelegramColors.DatePillBgDark
                else TelegramColors.DatePillBgLight,
            )
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.labelMedium,
            color = if (dark) TelegramColors.DatePillTextDark
            else TelegramColors.DatePillTextLight,
        )
    }
}

// ------------------------------------------------------------ date dividers

private sealed interface ChatItem {
    val key: String
    data class Divider(val label: String) : ChatItem {
        override val key: String = "div-$label"
    }
    data class Msg(
        val msg: MessagingEngine.MessageView,
        val isFirstInGroup: Boolean,
        val isLastInGroup: Boolean,
    ) : ChatItem {
        override val key: String = "msg-${msg.id}"
    }
}

/**
 * Newest-first message list with day divider items inserted and same-sender
 * run grouping computed (drives the 6dp near-corners and tail placement).
 */
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
    // Grouping is computed in chronological order (oldest-first).
    val chrono = messages
    val firstInRun = BooleanArray(chrono.size)
    val lastInRun = BooleanArray(chrono.size)
    for (i in chrono.indices) {
        val m = chrono[i]
        val prev = chrono.getOrNull(i - 1)
        val next = chrono.getOrNull(i + 1)
        val sameDayPrev = prev != null && dayKey(prev.timestamp) == dayKey(m.timestamp)
        val sameDayNext = next != null && dayKey(next.timestamp) == dayKey(m.timestamp)
        firstInRun[i] = prev == null || prev.isFromMe != m.isFromMe || !sameDayPrev
        lastInRun[i] = next == null || next.isFromMe != m.isFromMe || !sameDayNext
    }
    // Map chronological index -> reversed position.
    for ((revIdx, m) in reversed.withIndex()) {
        val chronoIdx = chrono.size - 1 - revIdx
        val day = dayKey(m.timestamp)
        if (day != lastDay) {
            out.add(ChatItem.Divider(dayLabel(m.timestamp, todayLabel, yesterdayLabel, locale)))
            lastDay = day
        }
        out.add(ChatItem.Msg(m, firstInRun[chronoIdx], lastInRun[chronoIdx]))
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

/** Date pill: 14sp white text on a dark translucent pill (Telegram service). */
@Composable
private fun DateDivider(label: String, dark: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (dark) TelegramColors.DatePillBgDark
                    else TelegramColors.DatePillBgLight,
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            fontSize = 14.sp,
            color = if (dark) TelegramColors.DatePillTextDark
            else TelegramColors.DatePillTextLight,
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
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                        fontSize = 18.sp,
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
                    focusedTextColor = if (dark) TelegramColors.InputTextDark
                    else TelegramColors.InputTextLight,
                    unfocusedTextColor = if (dark) TelegramColors.InputTextDark
                    else TelegramColors.InputTextLight,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { submit() },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank()) TelegramColors.SendButton
                        else (if (dark) TelegramColors.DatePillBgDark
                        else TelegramColors.DatePillBgLight).copy(alpha = 0.5f),
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
