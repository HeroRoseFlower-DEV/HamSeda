package com.hamseda.walkie

import com.hamseda.walkie.data.normalizeCodecPreference
import com.hamseda.walkie.data.normalizeLanguageCode
import com.hamseda.walkie.proto.CodecId
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun `codec preference accepts only supported values`() {
        assertEquals(CodecId.OPUS, normalizeCodecPreference(null))
        assertEquals(CodecId.OPUS, normalizeCodecPreference(CodecId.OPUS.toInt()))
        assertEquals(CodecId.PCM16, normalizeCodecPreference(CodecId.PCM16.toInt()))
        assertEquals(CodecId.OPUS, normalizeCodecPreference(127))
        assertEquals(CodecId.OPUS, normalizeCodecPreference(-1))
        assertEquals(CodecId.OPUS, normalizeCodecPreference(256))
    }

    @Test
    fun `language preference accepts only explicit supported locales`() {
        assertEquals("fa", normalizeLanguageCode("fa"))
        assertEquals("en", normalizeLanguageCode("en"))
        assertEquals("", normalizeLanguageCode(null))
        assertEquals("", normalizeLanguageCode(""))
        assertEquals("", normalizeLanguageCode("fa-IR"))
        assertEquals("", normalizeLanguageCode("../../en"))
    }
}