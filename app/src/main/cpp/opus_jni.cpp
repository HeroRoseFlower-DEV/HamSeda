#include <jni.h>
#include <cstdint>

#include "opus.h"

namespace {

template <typename T>
jlong toHandle(T* ptr) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ptr));
}

template <typename T>
T* fromHandle(jlong handle) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeCreateEncoder(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint sample_rate,
    jint channels,
    jint bitrate_bps,
    jint complexity) {
    int error = OPUS_OK;
    OpusEncoder* encoder = opus_encoder_create(
        sample_rate,
        channels,
        OPUS_APPLICATION_VOIP,
        &error);
    if (encoder == nullptr || error != OPUS_OK) {
        if (encoder != nullptr) opus_encoder_destroy(encoder);
        return 0;
    }

    const int bitrate_result = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate_bps));
    const int complexity_result = opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    const int signal_result = opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    const int vbr_result = opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    const int vbr_constraint_result = opus_encoder_ctl(encoder, OPUS_SET_VBR_CONSTRAINT(1));
    // DTX reduces bitrate during silence while retaining 20 ms cadence.
    const int dtx_result = opus_encoder_ctl(encoder, OPUS_SET_DTX(1));
    // A modest expected-loss estimate encourages SILK/LBRR redundancy when
    // useful. The receiver only consumes it for a single missing audio frame.
    const int fec_result = opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    const int packet_loss_result = opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10));
    const int max_bandwidth_result =
        opus_encoder_ctl(encoder, OPUS_SET_MAX_BANDWIDTH(OPUS_BANDWIDTH_FULLBAND));
    const int depth_result = opus_encoder_ctl(encoder, OPUS_SET_LSB_DEPTH(16));

    if (bitrate_result != OPUS_OK ||
        complexity_result != OPUS_OK ||
        signal_result != OPUS_OK ||
        vbr_result != OPUS_OK ||
        vbr_constraint_result != OPUS_OK ||
        dtx_result != OPUS_OK ||
        fec_result != OPUS_OK ||
        packet_loss_result != OPUS_OK ||
        max_bandwidth_result != OPUS_OK ||
        depth_result != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return toHandle(encoder);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeCreateDecoder(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint sample_rate,
    jint channels) {
    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(sample_rate, channels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        if (decoder != nullptr) opus_decoder_destroy(decoder);
        return 0;
    }
    return toHandle(decoder);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeEncode(
    JNIEnv* env,
    jobject /* thiz */,
    jlong encoder_handle,
    jshortArray pcm_array,
    jint frame_samples,
    jbyteArray output_array) {
    OpusEncoder* encoder = fromHandle<OpusEncoder>(encoder_handle);
    if (encoder == nullptr || pcm_array == nullptr || output_array == nullptr ||
        frame_samples <= 0 ||
        frame_samples > env->GetArrayLength(pcm_array) ||
        env->GetArrayLength(output_array) <= 0) {
        return OPUS_BAD_ARG;
    }

    jshort* pcm = env->GetShortArrayElements(pcm_array, nullptr);
    if (pcm == nullptr) return OPUS_ALLOC_FAIL;

    jbyte* output = env->GetByteArrayElements(output_array, nullptr);
    if (output == nullptr) {
        env->ReleaseShortArrayElements(pcm_array, pcm, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    const int encoded_bytes = opus_encode(
        encoder,
        reinterpret_cast<const opus_int16*>(pcm),
        frame_samples,
        reinterpret_cast<unsigned char*>(output),
        env->GetArrayLength(output_array));

    env->ReleaseShortArrayElements(pcm_array, pcm, JNI_ABORT);
    env->ReleaseByteArrayElements(output_array, output, 0);
    return encoded_bytes;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeDecode(
    JNIEnv* env,
    jobject /* thiz */,
    jlong decoder_handle,
    jbyteArray packet_array,
    jshortArray output_array,
    jint frame_samples,
    jboolean decode_fec) {
    OpusDecoder* decoder = fromHandle<OpusDecoder>(decoder_handle);
    if (decoder == nullptr || packet_array == nullptr || output_array == nullptr ||
        frame_samples <= 0 ||
        frame_samples > env->GetArrayLength(output_array) ||
        env->GetArrayLength(packet_array) <= 0) {
        return OPUS_BAD_ARG;
    }

    jbyte* packet = env->GetByteArrayElements(packet_array, nullptr);
    if (packet == nullptr) return OPUS_ALLOC_FAIL;

    jshort* output = env->GetShortArrayElements(output_array, nullptr);
    if (output == nullptr) {
        env->ReleaseByteArrayElements(packet_array, packet, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }

    const int decoded_samples = opus_decode(
        decoder,
        reinterpret_cast<const unsigned char*>(packet),
        env->GetArrayLength(packet_array),
        reinterpret_cast<opus_int16*>(output),
        frame_samples,
        decode_fec == JNI_TRUE ? 1 : 0);

    env->ReleaseByteArrayElements(packet_array, packet, JNI_ABORT);
    env->ReleaseShortArrayElements(output_array, output, 0);
    return decoded_samples;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeHasInbandFec(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray packet_array) {
    if (packet_array == nullptr) return JNI_FALSE;
    const jsize packet_size = env->GetArrayLength(packet_array);
    if (packet_size <= 0) return JNI_FALSE;

    jbyte* packet = env->GetByteArrayElements(packet_array, nullptr);
    if (packet == nullptr) return JNI_FALSE;
    const int result = opus_packet_has_lbrr(
        reinterpret_cast<const unsigned char*>(packet),
        packet_size);
    env->ReleaseByteArrayElements(packet_array, packet, JNI_ABORT);
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeDestroyEncoder(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong encoder_handle) {
    OpusEncoder* encoder = fromHandle<OpusEncoder>(encoder_handle);
    if (encoder != nullptr) opus_encoder_destroy(encoder);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeDestroyDecoder(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong decoder_handle) {
    OpusDecoder* decoder = fromHandle<OpusDecoder>(decoder_handle);
    if (decoder != nullptr) opus_decoder_destroy(decoder);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hamseda_walkie_audio_NativeOpusCodec_nativeVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(opus_get_version_string());
}
