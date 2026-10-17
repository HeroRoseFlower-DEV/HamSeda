package android.util

/**
 * Shadows the Android framework Log in JVM unit tests. Production code calls
 * Log.d/w/e/i on session/transport paths under test; without this shadow the
 * framework stub throws "not mocked". This replaces the blunt
 * unitTests.isReturnDefaultValues = true (which silently stubbed EVERY
 * android.* call) with an explicit, minimal no-op logger.
 */
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = 0
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun wtf(tag: String, msg: String): Int = 0
    @JvmStatic fun wtf(tag: String, msg: String, tr: Throwable): Int = 0
}
