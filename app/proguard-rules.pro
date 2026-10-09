# HamSeda ProGuard rules (release builds keep minify disabled for v1.0;
# these rules exist so a future minified release stays correct).

# Keep the vendored Concentus Opus codec (pure Java, reflection-free, but keep
# the entry points stable for safety).
-keep class org.concentus.OpusEncoder { *; }
-keep class org.concentus.OpusDecoder { *; }

# Keep serializable protocol/crypto entry points used across the session layer.
-keep class com.hamseda.walkie.proto.** { *; }
-keep class com.hamseda.walkie.crypto.** { *; }
