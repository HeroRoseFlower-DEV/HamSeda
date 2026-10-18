package com.hamseda.walkie.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
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
import com.hamseda.walkie.ui.components.HamSedaTopBar
import com.hamseda.walkie.ui.theme.MessagingColors
import com.hamseda.walkie.ui.vm.ChatViewModel
import com.hamseda.walkie.ui.vm.ChatVmFactory
import com.hamseda.walkie.ui.vm.VmDeps
import com.hamseda.walkie.util.toFaDigits
import java.text.SimpleDateFormat
import java.util.Date

/**
 * One chat thread. Violet identity: gradient own-bubbles, soft peer
 * bubbles, bouncing typing dots, tick states. Fully RTL.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val listState = rememberLazyListState()

    // New arrivals while open count as read (drives read receipts).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) vm.onMessagesVisible()
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            HamSedaTopBar(
                title = peerName,
                onBack = onBack,
                actions = {
                    val status = when {
                        peerTyping -> stringResource(R.string.msg_typing)
                        online -> stringResource(R.string.msg_online)
                        else -> stringResource(R.string.msg_offline)
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (peerTyping) MessagingColors.VioletPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
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
            if (!online) {
                OfflineStrip()
            }
            val reversed = remember(messages) { messages.reversed() }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (peerTyping) {
                    item(key = "typing") {
                        TypingBubble()
                    }
                }
                items(reversed, key = { it.id }) { msg ->
                    MessageBubble(msg = msg, dark = dark)
                }
            }
            MessageInput(
                onSend = { vm.send(it); vm.onTyping(false) },
                onTyping = { vm.onTyping(it) },
            )
        }
    }
}

@Composable
private fun OfflineStrip() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.msg_offline_banner),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageBubble(
    msg: MessagingEngine.MessageView,
    dark: Boolean,
) {
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val time = remember(msg.timestamp, locale) {
        SimpleDateFormat("HH:mm", locale)
            .format(Date(msg.timestamp)).toFaDigits()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isFromMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (msg.isFromMe) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            val bubbleModifier = if (msg.isFromMe) {
                Modifier
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MessagingColors.VioletPrimary,
                                MessagingColors.VioletDeep,
                            ),
                        ),
                    )
            } else {
                Modifier
                    .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .background(
                        if (dark) MessagingColors.VioletNightSoft
                        else MessagingColors.VioletFaint,
                    )
            }
            Box(modifier = bubbleModifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = msg.body,
                    color = if (msg.isFromMe) MessagingColors.VioletInk
                    else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                )
                if (msg.isFromMe) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTicks(state = msg.state)
                }
            }
            if (msg.isFromMe && msg.state == MessageState.QUEUED) {
                Text(
                    text = stringResource(R.string.msg_state_queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = MessagingColors.VioletPrimary,
                    fontSize = 11.sp,
                )
            }
            if (msg.isFromMe && msg.state == MessageState.FAILED) {
                Text(
                    text = stringResource(R.string.msg_state_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DeliveryTicks(state: Int) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val (glyph, color) = when (state) {
        MessageState.SENT -> "✓" to MessagingColors.TickIdle
        MessageState.DELIVERED -> "✓✓" to MessagingColors.TickIdle
        MessageState.READ -> "✓✓" to
            if (dark) MessagingColors.VioletBright else MessagingColors.TickRead
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

@Composable
private fun TypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { i -> BouncingDot(phase = i) }
            }
        }
    }
}

@Composable
private fun BouncingDot(phase: Int) {
    val transition = rememberInfiniteTransition(label = "typing$phase")
    val dy by transition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing, delayMillis = phase * 140),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotDy",
    )
    Box(
        modifier = Modifier
            .offset(y = dy.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(MessagingColors.VioletPrimary.copy(alpha = 0.75f)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInput(
    onSend: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    fun submit() {
        val t = text.trim()
        if (t.isEmpty()) return
        onSend(t)
        text = ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = {
                text = it
                onTyping(it.isNotBlank())
            },
            placeholder = { Text(stringResource(R.string.msg_input_hint)) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = if (dark) MessagingColors.VioletNightSoft
                else MessagingColors.VioletFaint,
                unfocusedContainerColor = if (dark) MessagingColors.VioletNightSoft
                else MessagingColors.VioletFaint,
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
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (text.isNotBlank()) {
                        Brush.linearGradient(
                            listOf(MessagingColors.VioletPrimary, MessagingColors.VioletDeep),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        )
                    },
                ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.msg_send),
                tint = if (text.isNotBlank()) MessagingColors.VioletInk
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
