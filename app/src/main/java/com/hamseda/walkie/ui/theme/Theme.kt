package com.hamseda.walkie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * HamSeda visual language: warm ivory surfaces, deep navy typography,
 * controlled turquoise/teal accents, one restrained warm accent reserved
 * for alerts. Bright/light first; dark mode fully supported.
 */
object HamSedaColors {
    // Light
    val IvoryBackground = Color(0xFFFDFBF6)
    val PaperSurface = Color(0xFFFFFFFF)
    val NavyInk = Color(0xFF16233D)
    val NavySoft = Color(0xFF3D4C6B)
    val TealPrimary = Color(0xFF0E7C7B)
    val TealDeep = Color(0xFF0A5E5D)
    val TealSoft = Color(0xFFE0F2F1)
    val WarmAlert = Color(0xFFE8833A)
    val ErrorRed = Color(0xFFB3261E)
    val SuccessGreen = Color(0xFF2E7D32)
    val DividerLight = Color(0xFFE9E2D6)

    // Dark
    val NightBackground = Color(0xFF0F1622)
    val NightSurface = Color(0xFF182234)
    val NightInk = Color(0xFFEDEFF5)
    val NightSoft = Color(0xFF9AA7C2)
    val TealBright = Color(0xFF5BD6CB)
    val TealNight = Color(0xFF0E7C7B)
}

/**
 * Faithful Telegram palette, reverse-engineered from the official Android
 * source (DrKLO/Telegram, master @ Oct 2026). Color tokens come from
 * `ThemeColors.createDefaultColors()` (light base defaults) and
 * `night.attheme` (dark); geometry from `MessageDrawable`
 * (bubbleRadius=17dp, nearRad=6dp, 8dp tail zone) and `ChatMessageCell`
 * (text 16sp, in-bubble time 12sp, date header 14sp).
 *
 * Notes on choices:
 * - Out-bubble is the iconic light green 0xFFEFFFDE (base default), not the
 *   "Blue" theme variant's light blue — the green is the recognized Telegram.
 * - Read ticks are blue 0xFF1B9BE6 (from the Blue theme's check color): the
 *   base theme uses the same green for sent and read, but the shipped app's
 *   familiar sent→delivered→read progression is green→green→blue.
 * - Date pills are white 14sp text on a dark translucent pill (the service
 *   background is wallpaper-derived in Telegram; this matches its look).
 */
object TelegramColors {
    // ---- light ----
    val ChatBackgroundLight = Color(0xFFE7EBF0)
    val InBubbleLight = Color(0xFFFFFFFF)          // chat_inBubble
    val OutBubbleLight = Color(0xFFEFFFDE)         // chat_outBubble
    val MessageTextLight = Color(0xFF000000)       // chat_messageTextIn/Out
    val InTimeTextLight = Color(0xFFA1AAB3)        // chat_inTimeText
    val OutTimeTextLight = Color(0xFF70B15C)       // chat_outTimeText
    val TickSentLight = Color(0xFF5DB050)          // chat_outSentCheck
    val TickReadLight = Color(0xFF1B9BE6)          // blue read check (Blue theme)
    val DatePillBgLight = Color(0x80000000)        // dark translucent service pill
    val DatePillTextLight = Color(0xFFFFFFFF)      // chat_serviceText, 14sp
    val InputBarLight = Color(0xFFFFFFFF)          // chat_messagePanelBackground
    val InputTextLight = Color(0xFF000000)         // chat_messagePanelText
    val InputHintLight = Color(0xFF858A84)         // chat_messagePanelHint
    val SendButton = Color(0xFF229AF0)             // chat_messagePanelSend
    val UnreadBadgeLight = Color(0xFF229AF0)       // chats_unreadCounter
    val DialogNameLight = Color(0xFF1A1D21)        // chats_name, 17sp
    val DialogMessageLight = Color(0xFF75787A)     // chats_message, 16sp
    val DialogDateLight = Color(0xFF848688)        // chats_date
    val HeaderTitleLight = Color(0xFF222222)
    val HeaderSubtitleLight = Color(0xFF79817E)    // actionBarDefaultSubtitle
    val OnlineBlueLight = Color(0xFF298ACF)        // chat_status
    val DividerLight = Color(0xFFD9D9D9)           // divider

    // ---- dark (night.attheme) ----
    val ChatBackgroundDark = Color(0xFF0E1621)
    val InBubbleDark = Color(0xFF1F2123)           // chat_inBubble
    val OutBubbleDark = Color(0xFF366CAF)          // chat_outBubble
    val MessageTextDark = Color(0xFFFAFAFA)        // chat_messageTextIn/Out
    val InTimeTextDark = Color(0xFF7D7F81)         // chat_inTimeText
    val OutTimeTextDark = Color(0xFF94D3F6)        // chat_outTimeText
    val TickSentDark = Color(0xE1C9F0FF)           // chat_outSentCheck
    val TickReadDark = Color(0xFFA6DBFF)           // chat_outSentCheckSelected
    val DatePillBgDark = Color(0x96373737)         // chat_serviceBackground
    val DatePillTextDark = Color(0xFFFFFFFF)
    val InputBarDark = Color(0xFF1E1E1F)           // chat_messagePanelBackground
    val InputTextDark = Color(0xFFFFFFFF)
    val InputHintDark = Color(0x64FFFFFF)          // chat_messagePanelHint
    val UnreadBadgeDark = Color(0xFF399EF3)        // chats_unreadCounter
    val DialogNameDark = Color(0xFFF1F1F1)         // chats_name
    val DialogMessageDark = Color(0xFF828282)      // chats_message
    val DialogDateDark = Color(0xFF787878)         // chats_date
    val HeaderTitleDark = Color(0xFFFFFFFF)        // actionBarDefaultTitle
    val HeaderSubtitleDark = Color(0x73F2F2F2)     // actionBarDefaultSubtitle
    val OnlineBlueDark = Color(0xFF74BDF9)         // chat_status
    val DividerDark = Color(0xFF242F3D)

    // ---- bubble geometry (MessageDrawable; SharedConfig.bubbleRadius=17) ----
    /** Default bubble corner radius. */
    val BubbleRadius = 17.dp
    /** Radius of corners adjacent to a grouped message. */
    val BubbleNearRadius = 6.dp
    /** Horizontal zone reserved for the tail on the outer edge. */
    val BubbleTailZone = 8.dp
}

private val LightScheme = lightColorScheme(
    primary = HamSedaColors.TealPrimary,
    onPrimary = Color.White,
    primaryContainer = HamSedaColors.TealSoft,
    onPrimaryContainer = HamSedaColors.TealDeep,
    secondary = HamSedaColors.NavySoft,
    background = HamSedaColors.IvoryBackground,
    onBackground = HamSedaColors.NavyInk,
    surface = HamSedaColors.PaperSurface,
    onSurface = HamSedaColors.NavyInk,
    surfaceVariant = Color(0xFFF4EFE4),
    onSurfaceVariant = HamSedaColors.NavySoft,
    tertiary = HamSedaColors.WarmAlert,
    error = HamSedaColors.ErrorRed,
    outline = HamSedaColors.DividerLight,
)

private val DarkScheme = darkColorScheme(
    primary = HamSedaColors.TealBright,
    onPrimary = Color(0xFF062B2A),
    primaryContainer = Color(0xFF0B3B3A),
    onPrimaryContainer = HamSedaColors.TealBright,
    secondary = HamSedaColors.NightSoft,
    background = HamSedaColors.NightBackground,
    onBackground = HamSedaColors.NightInk,
    surface = HamSedaColors.NightSurface,
    onSurface = HamSedaColors.NightInk,
    surfaceVariant = Color(0xFF1E2A40),
    onSurfaceVariant = HamSedaColors.NightSoft,
    tertiary = HamSedaColors.WarmAlert,
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF2A3852),
)

@Composable
fun HamSedaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
