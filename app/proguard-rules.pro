# HamSeda ProGuard rules (release builds keep minify disabled as of v1.2;
# these rules exist so a future minified release stays correct).

# Keep the JNI entry points of the native Opus codec. Without this, a
# minified build would mangle the native method names and every call would
# die with UnsatisfiedLinkError at runtime (total audio loss).
-keep class com.hamseda.walkie.audio.NativeOpusCodec { *; }

# Keep serializable protocol/crypto entry points used across the session layer.
-keep class com.hamseda.walkie.proto.** { *; }
-keep class com.hamseda.walkie.crypto.** { *; }

# Room: the database, DAOs, entities and the generated *_Impl classes are
# reached reflectively (Room instantiates the impl classes by name and
# reads entity/DAO metadata at runtime). Without these keeps a future
# minified release would rename them and messaging persistence would die
# with a "cannot find implementation" crash on first launch.
-keep class com.hamseda.walkie.data.msg.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
