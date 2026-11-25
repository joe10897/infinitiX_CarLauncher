package com.infiniti.y51send

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSenderTest {

    @Test
    fun pairingCode_normalizesFormatsAndMatchesCarTopicConvention() {
        assertTrue(RemoteNavSender.isValidPairingCode("y51-7k4m-9p2x"))
        assertFalse(RemoteNavSender.isValidPairingCode("123"))

        assertEquals("Y51-7K4M-9P2X", RemoteNavSender.formatPairingCode("y51 7k4m 9p2x"))
        assertEquals(
            "infinitiy51-nav-y517k4m9p2x",
            RemoteNavSender.topicForPairingCode("Y51-7K4M-9P2X")
        )
        assertEquals(
            "https://ntfy.sh/infinitiy51-nav-y517k4m9p2x",
            RemoteNavSender.buildPostUrl("y51-7k4m-9p2x")
        )
    }

    @Test
    fun buildPayloadJson_encodesVersionRawAndTimestamp() {
        val jsonStr = RemoteNavSender.buildPayloadJson(" 台北101 https://maps.app.goo.gl/abc ", 1764096000000L)
        val obj = JSONObject(jsonStr)
        assertEquals(1, obj.getInt("v"))
        assertEquals("台北101 https://maps.app.goo.gl/abc", obj.getString("raw"))
        assertEquals(1764096000000L, obj.getLong("ts"))
    }
}
