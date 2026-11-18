package com.infiniti.m25launcher

import com.infiniti.m25launcher.VoiceCommandRouter.MediaCommand
import com.infiniti.m25launcher.VoiceCommandRouter.VoiceAction
import com.infiniti.m25launcher.VoiceCommandRouter.VolumeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandRouterTest {

    @Test
    fun parse_navigationWithSplitExtractsDestination() {
        assertEquals(
            VoiceAction.NavigateSplit("台北101"),
            VoiceCommandRouter.parse("導航到台北101。")
        )
        assertEquals(
            VoiceAction.NavigateSplit("桃園機場第一航廈"),
            VoiceCommandRouter.parse("帶我去桃園機場第一航廈")
        )
        assertEquals(
            VoiceAction.NavigateSplit("Taipei Station"),
            VoiceCommandRouter.parse("navigate to Taipei Station")
        )
    }

    @Test
    fun parse_spotifySearchAndMediaTransportPrecedence() {
        assertEquals(
            VoiceAction.MediaControl(MediaCommand.PLAY),
            VoiceCommandRouter.parse("繼續播放")
        )
        assertEquals(
            VoiceAction.MediaControl(MediaCommand.PAUSE),
            VoiceCommandRouter.parse("暫停")
        )
        assertEquals(
            VoiceAction.MediaControl(MediaCommand.NEXT),
            VoiceCommandRouter.parse("下一首")
        )
        assertEquals(
            VoiceAction.MediaControl(MediaCommand.PREV),
            VoiceCommandRouter.parse("上一首")
        )
        assertEquals(
            VoiceAction.PlaySpotifySearch("周杰倫 稻香"),
            VoiceCommandRouter.parse("用Spotify播放周杰倫 稻香")
        )
        assertEquals(
            VoiceAction.PlaySpotifySearch("告五人"),
            VoiceCommandRouter.parse("我想聽告五人！")
        )
    }

    @Test
    fun parse_automationAndHeadUnitShortcuts() {
        assertEquals(VoiceAction.StartAutoDriveRoutine, VoiceCommandRouter.parse("啟動一鍵出發"))
        assertEquals(VoiceAction.StartSplitNavigation, VoiceCommandRouter.parse("開啟分屏導航"))
        assertEquals(VoiceAction.LaunchSpeedcam, VoiceCommandRouter.parse("打開神盾測速"))
        assertEquals(VoiceAction.LaunchVehicleInfo, VoiceCommandRouter.parse("查看原車資訊"))
        assertEquals(VoiceAction.GoHome, VoiceCommandRouter.parse("回桌面"))
    }

    @Test
    fun parse_volumeControlsDistinguishMuteAndUnmute() {
        assertEquals(VoiceAction.VolumeControl(VolumeDirection.UNMUTE), VoiceCommandRouter.parse("取消靜音"))
        assertEquals(VoiceAction.VolumeControl(VolumeDirection.MUTE), VoiceCommandRouter.parse("靜音"))
        assertEquals(VoiceAction.VolumeControl(VolumeDirection.UP), VoiceCommandRouter.parse("大聲一點"))
        assertEquals(VoiceAction.VolumeControl(VolumeDirection.UP), VoiceCommandRouter.parse("聲音大一點"))
        assertEquals(VoiceAction.VolumeControl(VolumeDirection.DOWN), VoiceCommandRouter.parse("聲音小一點"))
    }

    @Test
    fun parseCandidates_picksMatchingHypothesisOrFallsBack() {
        val matched = VoiceCommandRouter.parseCandidates(listOf("不認識的字", "下一首"))
        assertEquals(VoiceAction.MediaControl(MediaCommand.NEXT), matched)

        val fallback = VoiceCommandRouter.parseCandidates(listOf("今天台北天氣如何", "今天台北天氣好嗎"))
        assertTrue(fallback is VoiceAction.FallbackAssistant)
        assertEquals("今天台北天氣如何", (fallback as VoiceAction.FallbackAssistant).rawQuery)
    }
}
