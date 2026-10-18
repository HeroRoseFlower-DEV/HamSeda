package com.hamseda.walkie.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hamseda.walkie.data.msg.MessageState
import com.hamseda.walkie.ui.theme.TelegramColors

/**
 * Faithful Telegram message bubble, reimplemented from the official Android
 * source's geometry (org.telegram.ui.ActionBar.MessageDrawable and
 * Cells/ChatMessageCell) — measurements only, no copied code.
 *
 * Geometry tokens (dp):
 * - corner radius 17 (SharedConfig.bubbleRadius), 6 for corners adjacent to a
 *   grouped message (nearRad)
 * - 8dp tail zone on the outer edge; tail tip ~2.6dp from the outer edge,
 *   curved hook ~10dp tall (MessageDrawable.generatePath, TYPE_TEXT)
 * - text 16sp; in-bubble time 12sp; content padding 11dp on the leading edge
 *   for outgoing, 17dp for incoming (ChatMessageCell.layoutTextXY), 7dp top
 *   / 6dp bottom
 * - time + ticks sit inline at the end of the text flow (Telegram wraps the
 *   last line around them); replicated with InlineTextContent
 * - the tail is drawn only on the last message of a same-sender run
 */
@Composable
fun TelegramBubble(
    body: String,
    isFromMe: Boolean,
    time: String,
    state: Int,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        isFromMe && dark -> TelegramColors.OutBubbleDark
        isFromMe -> TelegramColors.OutBubbleLight
        dark -> TelegramColors.InBubbleDark
        else -> TelegramColors.InBubbleLight
    }
    val textColor = if (dark) TelegramColors.MessageTextDark else TelegramColors.MessageTextLight
    val timeColor = when {
        isFromMe && dark -> TelegramColors.OutTimeTextDark
        isFromMe -> TelegramColors.OutTimeTextLight
        dark -> TelegramColors.InTimeTextDark
        else -> TelegramColors.InTimeTextLight
    }
    val tickSent = if (dark) TelegramColors.TickSentDark else TelegramColors.TickSentLight
    val tickRead = if (dark) TelegramColors.TickReadDark else TelegramColors.TickReadLight
    // chat_inBubbleShadow / chat_outBubbleShadow, drawn at low alpha.
    val shadow = when {
        isFromMe && dark -> Color(0x32000000)
        isFromMe -> Color(0xFF1E750C)
        dark -> Color(0x32000000)
        else -> Color(0xFF1D3753)
    }

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The canonical path draws the tail on the right; mirror when the tail
    // belongs on the left: LTR+incoming or RTL+outgoing.
    val mirror = (isRtl == isFromMe)
    val drawTail = isLastInGroup
    val topNear = !isFirstInGroup
    val bottomNear = !isLastInGroup

    // Inline time + ticks at the end of the text flow, like Telegram.
    val annotated = buildAnnotatedString {
        append(body)
        append("  ")
        appendInlineContent("meta", time)
    }
    val metaWidth = if (isFromMe) 64.sp else 38.sp
    val inlineContent = mapOf(
        "meta" to InlineTextContent(
            Placeholder(metaWidth, 15.sp, PlaceholderVerticalAlign.Bottom),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = time,
                    style = TextStyle(fontSize = 12.sp, color = timeColor),
                    maxLines = 1,
                )
                if (isFromMe && state >= MessageState.SENT) {
                    Spacer(Modifier.width(4.dp))
                    TelegramCheckmarks(state = state, tickSent = tickSent, tickRead = tickRead)
                }
            }
        },
    )

    Box(modifier = modifier.fillMaxWidth(0.8f)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val path = telegramBubblePath(
                topNear = topNear,
                bottomNear = bottomNear,
                drawTail = drawTail,
            )
            if (mirror) {
                scale(scaleX = -1f, scaleY = 1f, pivot = Offset(size.width / 2f, 0f)) {
                    drawBubbleWithShadow(path, bg, shadow)
                }
            } else {
                drawBubbleWithShadow(path, bg, shadow)
            }
        }
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = TextStyle(fontSize = 16.sp, color = textColor),
            modifier = Modifier.padding(
                // Leading-edge padding from ChatMessageCell.layoutTextXY:
                // 11dp outgoing, 17dp incoming (start is direction-aware).
                start = if (isFromMe) 11.dp else 17.dp,
                end = 11.dp,
                top = 7.dp,
                bottom = 6.dp,
            ),
        )
    }
}

private fun DrawScope.drawBubbleWithShadow(path: Path, bg: Color, shadow: Color) {
    translate(left = 0f, top = 1.dp.toPx()) {
        drawPath(path, shadow.copy(alpha = 0.18f))
    }
    drawPath(path, bg)
}

/**
 * Builds the Telegram bubble path (body + tail as one path) in a canonical
 * orientation with the tail zone on the right; the caller mirrors for RTL.
 * Follows MessageDrawable.generatePath() for TYPE_TEXT: 2dp inset, 17dp
 * main radius, 6dp near radius, 8dp tail zone, tail tip 2.6dp from the edge.
 */
private fun DrawScope.telegramBubblePath(
    topNear: Boolean,
    bottomNear: Boolean,
    drawTail: Boolean,
): Path {
    val w = size.width
    val h = size.height
    val pad = 2.dp.toPx()
    val rad = TelegramColors.BubbleRadius.toPx()
    val near = TelegramColors.BubbleNearRadius.toPx()
    val tailZone = TelegramColors.BubbleTailZone.toPx()

    val bodyRight = w - tailZone
    val topR = if (topNear) near else rad
    val botR = if (bottomNear) near else rad

    return Path().apply {
        moveTo(pad + rad, pad)
        // Top edge.
        lineTo(bodyRight - topR, pad)
        arcTo(Rect(bodyRight - 2 * topR, pad, bodyRight, pad + 2 * topR), 270f, 90f, false)
        if (drawTail) {
            // Right edge down to the tail base.
            val tailTop = h - pad - 12.dp.toPx()
            lineTo(bodyRight, tailTop)
            // Tail hook: sweep out to the tip (2.6dp from the outer edge),
            // then back to the bottom edge.
            val tipX = w - 2.6f.dp.toPx()
            val tipY = h - pad
            cubicTo(
                bodyRight + 1.dp.toPx(), tailTop + 4.dp.toPx(),
                tipX - 3.dp.toPx(), tipY - 3.dp.toPx(),
                tipX, tipY,
            )
            quadraticTo(
                bodyRight + 2.dp.toPx(), tipY,
                bodyRight - 6.dp.toPx(), tipY,
            )
        } else {
            // Bottom-right corner arc.
            val br = if (bottomNear) near else rad
            lineTo(bodyRight, h - pad - br)
            arcTo(
                Rect(bodyRight - 2 * br, h - pad - 2 * br, bodyRight, h - pad),
                0f, 90f, false,
            )
        }
        // Bottom edge.
        lineTo(pad + botR, h - pad)
        arcTo(Rect(pad, h - pad - 2 * botR, pad + 2 * botR, h - pad), 90f, 90f, false)
        // Left edge up.
        lineTo(pad, pad + rad)
        arcTo(Rect(pad, pad, pad + 2 * rad, pad + 2 * rad), 180f, 90f, false)
        close()
    }
}

/** Vector check marks: single (sent) / double (delivered, read). */
@Composable
private fun TelegramCheckmarks(
    state: Int,
    tickSent: Color,
    tickRead: Color,
    modifier: Modifier = Modifier,
) {
    val color = if (state >= MessageState.READ) tickRead else tickSent
    val double = state >= MessageState.DELIVERED
    Canvas(modifier = modifier.size(if (double) 20.dp else 12.dp, 12.dp)) {
        val stroke = 1.8f.dp.toPx()
        fun check(offsetX: Float) {
            val p = Path().apply {
                moveTo(offsetX + 1.dp.toPx(), 6.dp.toPx())
                lineTo(offsetX + 4.5f.dp.toPx(), 9.5f.dp.toPx())
                lineTo(offsetX + 11.dp.toPx(), 2.5f.dp.toPx())
            }
            drawPath(p, color, style = Stroke(width = stroke))
        }
        if (double) {
            check(0f)
            check(7.dp.toPx())
        } else {
            check(0f)
        }
    }
}
