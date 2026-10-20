package com.hamseda.walkie.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hamseda.walkie.R
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.messaging.MessagingEngine
import com.hamseda.walkie.ui.components.ChatWallpaper
import com.hamseda.walkie.ui.components.PeerAvatar
import com.hamseda.walkie.ui.components.TelegramBubble
import com.hamseda.walkie.ui.theme.TelegramColors
import com.hamseda.walkie.ui.vm.ChatViewModel
import com.hamseda.walkie.ui.vm.ChatVmFactory
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.util.toLocalizedDigits
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
    dark: Boolean,
    onBack: () -> Unit,
    // Keyed by peer: without key=, the ViewModelStore (activity-scoped,
    // class-keyed) would return chat A's VM when opening chat B — rendering
    // A's messages and sending to A. Each conversation gets its own VM.
    vm: ChatViewModel = viewModel(key = "chat/$peerId", factory = ChatVmFactory(deps, peerId)),
) {
    // The VM is activity-scoped, so onCleared never fires on back-navigation.
    // Manage the engine's active peer with the composition lifecycle instead:
    // set on enter (covers VM reuse — init ran only once), clear on leave so
    // typing indicators and read receipts stop when the chat is not visible.
    DisposableEffect(peerId) {
        vm.activate()
        onDispose {
            // Leaving with a non-empty draft must not leave the peer's
            // "typing…" indicator lit until its TTL expires (round-7
            // L11): send the stop frame BEFORE clearing the active peer,
            // while setTyping's peer check still passes for this chat.
            // Harmless when nothing was typed.
            vm.onTyping(false)
            vm.clearActivePeer()
        }
    }

    val messages by vm.messages.collectAsStateWithLifecycle()
    val peerTyping by vm.peerTyping.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val peerProfile by vm.peerProfile.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    // `dark` is the RESOLVED theme (user's ThemeMode override applied by
    // MainActivity), passed in — never isSystemInDarkTheme(): an explicit
    // Dark choice on a light-system phone (or vice versa) must theme this
    // screen like every other.
    val listState = rememberLazyListState()

    val displayName = peerProfile?.displayName?.takeIf { it.isNotEmpty() } ?: peerName

    // New arrivals while open count as read (drives read receipts).
    // Auto-scroll only when already at the newest messages (index 0 in the
    // reversed list) — never yank the scroll away from history being read.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            vm.onMessagesVisible()
            if (listState.firstVisibleItemIndex == 0) {
                listState.animateScrollToItem(0)
            }
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
        // Bottom inset applied exactly once: navigation-bar height with the
        // keyboard closed, keyboard height with it open (ime already spans
        // from the screen bottom). Top/sides still come from the inner
        // Scaffold, but its own bottom inset (the nav bar) is NOT taken here,
        // and the root layout skips its bottom padding on the chat screen
        // too (see HamSedaRoot) — otherwise the two stack a second copy
        // (nested-Scaffold double inset, ~1 nav-bar of dead space above
        // the input, as seen in the user's screenshot).
        // The bottom inset itself is a layout-phase Spacer at the end of
        // the Column (below), NOT a value read here: reading WindowInsets
        // during composition recomposed the whole screen (and redrew the
        // wallpaper) on every IME animation frame.
        val layoutDir = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                // absolutePadding with the absolute values is identical to
                // padding(padding) for top/sides; the bottom comes from the
                // layout-phase inset Spacer at the end of the Column.
                .absolutePadding(
                    left = padding.calculateLeftPadding(layoutDir),
                    top = padding.calculateTopPadding(),
                    right = padding.calculateRightPadding(layoutDir),
                    bottom = 0.dp,
                ),
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
            // Wallpaper behind the message list, like Telegram's patterned chat
            // background (original doodles drawn in ChatWallpaper — no
            // Telegram assets). The list itself stays transparent on top.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ChatWallpaper(dark = dark, modifier = Modifier.matchParentSize())
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
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
                                    .format(Date(msg.timestamp)).toLocalizedDigits(locale)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                // Telegram never flips message sides with layout direction:
                                // outgoing is always on the right, incoming always on the
                                // left. Direction-aware Start/End put outgoing on the left
                                // in Persian RTL (the PR #43 regression) — use absolute sides.
                                horizontalArrangement = if (msg.isFromMe) Arrangement.Absolute.Right
                                else Arrangement.Absolute.Left,
                            ) {
                                // Children align to the PHYSICAL right in both
                                // locales: the QUEUED/FAILED label belongs over
                                // the outgoing bubble's right edge. A default
                                // (Start) alignment + a fillMaxWidth label used
                                // to stretch this Column full-width and park the
                                // bubble at Start — physical LEFT in LTR.
                                Column(horizontalAlignment = AbsoluteRightAlignment) {
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
                                            textAlign = TextAlign.Right,
                                            modifier = Modifier
                                                .absolutePadding(right = 12.dp, bottom = 2.dp),
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
            }
            TelegramInput(
                peerId = peerId,
                onSend = { vm.send(it); vm.onTyping(false) },
                onTyping = { vm.onTyping(it) },
                dark = dark,
            )
            // The screen's single bottom inset, resolved in the LAYOUT
            // phase: max(ime, navigationBars) via the insets union — the
            // input rides above the keyboard when open and above the
            // system nav when closed, without recomposing per IME frame.
            Spacer(
                Modifier.windowInsetsBottomHeight(
                    WindowInsets.ime.union(WindowInsets.navigationBars),
                ),
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
        SimpleDateFormat("HH:mm", locale).format(Date(lastSeen)).toLocalizedDigits(locale)
    } else {
        SimpleDateFormat("d MMM", locale).format(Date(lastSeen)).toLocalizedDigits(locale)
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
                    contentDescription = stringResource(R.string.back),
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

/**
 * Physical-right horizontal alignment, direction-independent: returns
 * `space - size` regardless of layout direction. Used for the outgoing
 * message column so the bubble + its QUEUED/FAILED label hug the right
 * edge in BOTH locales (Telegram never mirrors message sides).
 */
private object AbsoluteRightAlignment : Alignment.Horizontal {
    override fun align(size: Int, space: Int, layoutDirection: LayoutDirection): Int =
        space - size
}

internal sealed interface ChatItem {
    val key: String
    // Keyed by day key, not the display label: dayLabel drops the year for
    // old dates ("d MMMM"), so same-day-different-year dividers would collide
    // and crash the lazy list on duplicate keys.
    data class Divider(val day: String, val label: String) : ChatItem {
        override val key: String = "div-$day"
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
 * Internal for the divider-ordering regression test.
 */
internal fun withDateDividers(
    messages: List<MessagingEngine.MessageView>,
    todayLabel: String,
    yesterdayLabel: String,
    locale: Locale,
): List<ChatItem> {
    // messages arrive oldest-first; reverse for the reversed LazyColumn.
    val reversed = messages.reversed()
    val out = ArrayList<ChatItem>(reversed.size + 4)
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
    // The list is newest-first for the reversed LazyColumn (index 0 renders
    // at the bottom). A day's divider is appended AFTER its messages in
    // this order, so visually it renders ABOVE (before) them.
    for ((revIdx, m) in reversed.withIndex()) {
        val chronoIdx = chrono.size - 1 - revIdx
        val day = dayKey(m.timestamp)
        out.add(ChatItem.Msg(m, firstInRun[chronoIdx], lastInRun[chronoIdx]))
        val next = reversed.getOrNull(revIdx + 1)
        if (next == null || dayKey(next.timestamp) != day) {
            out.add(ChatItem.Divider(day, dayLabel(m.timestamp, todayLabel, yesterdayLabel, locale)))
        }
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
    return SimpleDateFormat("d MMMM", locale).format(Date(ts)).toLocalizedDigits(locale)
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
    peerId: String,
    onSend: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    dark: Boolean,
) {
    // rememberSaveable: rotating the phone must not discard the typed draft.
    // Keyed by peer: an unkeyed draft survives navigation (same composition
    // slot in MainActivity), so text typed for A reappeared in B's input —
    // and one Send tap away from going to the wrong person. (MainActivity
    // also keys the whole ChatScreen by peer; this is the second lock.)
    var text by rememberSaveable(key = "chatDraft/$peerId") { mutableStateOf("") }

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        onSend(t)
        text = ""
    }

    val barBg = if (dark) TelegramColors.InputBarDark else TelegramColors.InputBarLight

    Surface(color = barBg, shadowElevation = 4.dp) {
        Row(
            // No imePadding() here: the screen-level bottom inset
            // (max of ime / navigation bars) already rides the bar above the
            // keyboard when open and above the system nav when closed.
            // Adding imePadding() as well would double the keyboard inset.
            modifier = Modifier
                .fillMaxWidth()
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
