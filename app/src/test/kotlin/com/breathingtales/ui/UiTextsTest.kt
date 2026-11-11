package com.breathingtales.ui

import com.breathingtales.domain.model.Interaction
import com.breathingtales.domain.model.InteractionType
import com.breathingtales.domain.safety.NotParkedReason
import com.breathingtales.domain.usecase.AbortReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiTextsTest {

    @Test
    fun `복원 안내는 정차하면 되돌릴 항목을 따로 알리고 되돌리면 지운다`() {
        val failures = listOf("cabinLight", "readingLight[16]", "fanSpeed")
        val waiting = setOf("cabinLight", "readingLight[16]")
        assertEquals(
            "실내 상태 일부를 되돌리지 못했어요: fanSpeed\n기어를 P로 바꾸면 마저 되돌릴게요: cabinLight, readingLight[16]",
            UiTexts.restoreFailure(failures, waiting, pending = waiting.toList()),
        )
        assertEquals("실내 상태 일부를 되돌리지 못했어요: fanSpeed", UiTexts.restoreFailure(failures, waiting, pending = emptyList()))
        assertNull(UiTexts.restoreFailure(listOf("cabinLight"), setOf("cabinLight"), pending = emptyList()))
        assertNull(UiTexts.restoreFailure(emptyList(), emptySet(), emptyList()))
    }

    @Test
    fun `정차 중이면 안내가 없다`() {
        assertNull(UiTexts.parkGuide(emptySet()))
    }

    @Test
    fun `주행 관련 이유는 기어 P 안내`() {
        assertEquals(
            "차를 세우고 기어를 P로 놓아 주세요",
            UiTexts.parkGuide(setOf(NotParkedReason.NOT_IN_PARK, NotParkedReason.MOVING, NotParkedReason.UX_RESTRICTED)),
        )
    }

    @Test
    fun `문만 열려 있으면 문 안내`() {
        assertEquals("문을 닫아 주세요", UiTexts.parkGuide(setOf(NotParkedReason.DOOR_OPEN)))
    }

    @Test
    fun `주행과 문이 함께면 둘 다 안내`() {
        assertEquals(
            "차를 세우고 기어를 P로 놓은 뒤 문을 닫아 주세요",
            UiTexts.parkGuide(setOf(NotParkedReason.MOVING, NotParkedReason.DOOR_OPEN)),
        )
    }

    @Test
    fun `부모가 멈추면 안내 없이 돌아간다`() {
        assertNull(UiTexts.abortMessage(AbortReason.StoppedByParent))
        assertNull(UiTexts.abortMessage(AbortReason.Cancelled))
    }

    @Test
    fun `주차 브레이크 해제가 다른 이유보다 먼저`() {
        val reason = AbortReason.NotParked(setOf(NotParkedReason.PARKING_BRAKE_RELEASED, NotParkedReason.MOVING))
        assertEquals("주차 브레이크가 풀려서 이야기를 멈췄어요", UiTexts.abortMessage(reason))
    }

    @Test
    fun `문 열림만으로 멈춤`() {
        assertEquals("문이 열려서 이야기를 멈췄어요", UiTexts.abortMessage(AbortReason.NotParked(setOf(NotParkedReason.DOOR_OPEN))))
    }

    @Test
    fun `아이 이름 치환, 없으면 친구`() {
        assertEquals("안녕, 하늘!", UiTexts.fillChildName("안녕, {childName}!", " 하늘 "))
        assertEquals("안녕, 친구!", UiTexts.fillChildName("안녕, {childName}!", ""))
    }

    @Test
    fun `참여 안내는 첫 키워드를 쓴다`() {
        val interaction = Interaction(InteractionType.SHOUT_OR_BLOW, listOf("바람아 불어라", "바람아"), emptyList(), "바람 불기", 10)
        assertEquals("“바람아 불어라!” 외치거나  “후~” 불어요", UiTexts.interactionGuide(interaction))
    }

    @Test
    fun `길이는 분 단위로 반올림`() {
        assertEquals("약 6분", UiTexts.duration(330))
        assertEquals("1분 이내", UiTexts.duration(20))
    }
}
