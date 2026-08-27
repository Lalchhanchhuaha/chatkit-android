package com.chatkit.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCameraFilesTest {
    @Test
    fun captureIdsAreLowercaseUuids() {
        val id = ChatCameraFiles.newCaptureId()
        assertEquals(id, id.lowercase())
        assertTrue(id.contains('-'))
        assertEquals(36, id.length)
    }

    @Test
    fun photoAndVideoFilesUseChatCameraPrefix() {
        val cache = createTempDir()
        try {
            val (photoId, photo) = ChatCameraFiles.photoFile(cache)
            val (videoId, video) = ChatCameraFiles.videoFile(cache)
            assertEquals("chat-camera-$photoId.jpg", photo.name)
            assertEquals("chat-camera-$videoId.mp4", video.name)
            assertTrue(ChatCameraFiles.isChatCameraFile(photo))
            assertTrue(ChatCameraFiles.isChatCameraFile(video))
            assertFalse(ChatCameraFiles.isChatCameraFile(cache.resolve("other.jpg")))
        } finally {
            cache.deleteRecursively()
        }
    }
}

class VideoTrimRangeTest {
    @Test
    fun minimumDurationIsClampedBetweenThreeTenthsAndOneSecond() {
        assertEquals(0.3, minimumTrimDurationSeconds(1.0), 1e-6)
        assertEquals(0.5, minimumTrimDurationSeconds(10.0), 1e-6)
        assertEquals(1.0, minimumTrimDurationSeconds(100.0), 1e-6)
    }

    @Test
    fun clampEnforcesMinimumWindow() {
        val range = clampTrimRange(0.0, 0.1, 10.0)
        assertEquals(0.5, range.durationSeconds, 1e-6)
    }

    @Test
    fun moveWindowPreservesDuration() {
        val original = VideoTrimRange(1.0, 3.0)
        val moved = moveTrimWindow(original, 2.0, 10.0)
        assertEquals(2.0, moved.durationSeconds, 1e-6)
        assertEquals(3.0, moved.startSeconds, 1e-6)
        assertEquals(5.0, moved.endSeconds, 1e-6)
    }

    @Test
    fun fullRangeDetectionUsesEpsilon() {
        val range = VideoTrimRange(0.0, 10.0)
        assertTrue(range.isFullRange(10.0))
        assertFalse(VideoTrimRange(0.5, 9.0).isFullRange(10.0))
    }

    @Test
    fun clampOnTinyClipReturnsEntireDuration() {
        val range = clampTrimRange(0.0, 0.2, 0.2)
        assertEquals(0.0, range.startSeconds, 1e-6)
        assertEquals(0.2, range.endSeconds, 1e-6)
    }
}
