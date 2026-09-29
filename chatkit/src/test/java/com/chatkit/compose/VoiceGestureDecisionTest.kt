package com.chatkit.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGestureDecisionTest {
    @Test
    fun horizontalDragArmsCancel() {
        val decision = voiceGestureDecision(-45f, -20f, 40f, 50f, 110f)
        assertTrue(decision.cancelArmed)
        assertFalse(decision.lockArmed)
        assertFalse(decision.cancelsImmediately)
    }

    @Test
    fun verticalDragArmsLock() {
        val decision = voiceGestureDecision(-15f, -60f, 40f, 50f, 110f)
        assertFalse(decision.cancelArmed)
        assertTrue(decision.lockArmed)
    }

    @Test
    fun diagonalLeftDragPrefersCancelLikeIos() {
        val decision = voiceGestureDecision(-60f, -70f, 40f, 50f, 110f)
        assertTrue(decision.cancelArmed)
        assertFalse(decision.lockArmed)
    }

    @Test
    fun farLeftDragCancelsImmediately() {
        val decision = voiceGestureDecision(-112f, -5f, 40f, 50f, 110f)
        assertTrue(decision.cancelArmed)
        assertTrue(decision.cancelsImmediately)
    }
}
