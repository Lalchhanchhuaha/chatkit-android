package com.chatkit.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaAspectRatioTest {
    @Test
    fun uprightDisplaySizeSwapsForNinetyAndTwoSeventy() {
        assertEquals(1080 to 1920, uprightDisplaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, uprightDisplaySize(1920, 1080, 270))
        assertEquals(1920 to 1080, uprightDisplaySize(1920, 1080, 0))
        assertEquals(1920 to 1080, uprightDisplaySize(1920, 1080, 180))
    }

    @Test
    fun aspectRatioFromSizeRejectsInvalid() {
        assertNull(aspectRatioFromSize(0, 100))
        assertNull(aspectRatioFromSize(100, 0))
        assertEquals(1.5f, aspectRatioFromSize(1500, 1000))
    }
}
