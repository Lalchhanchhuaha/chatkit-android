package com.chatkit.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.time.Instant

class ChatModelsTest {
    @Test
    fun attachmentKindsUseMimeTypeOrExtension() {
        assertTrue(ChatAttachment("1", "photo.bin", "image/jpeg").isImage)
        assertTrue(ChatAttachment("2", "clip.MP4").isVideo)
        assertTrue(ChatAttachment("3", "voice.opus?token=x").isAudio)
        assertFalse(ChatAttachment("4", "notes.pdf", "application/pdf").isImage)
    }

    @Test
    fun incomingDirectionIsExposed() {
        assertTrue(ChatMessage("1", "Hi", Instant.EPOCH, MessageDirection.Incoming).isIncoming)
        assertFalse(ChatMessage("2", "Hi", Instant.EPOCH, MessageDirection.Outgoing).isIncoming)
    }

    @Test
    fun mimeTypeTakesPriorityOverExtension() {
        assertFalse(ChatAttachment("1", "misleading.jpg", "application/pdf").isImage)
    }

    @Test
    fun progressIsClampedForRendering() {
        assertEquals(0f, TransferState.Uploading(-2f).clampedProgress())
        assertEquals(1f, TransferState.Uploading(4f).clampedProgress())
    }

    @Test
    fun optimisticRowsAreRemovedWhenHostEchoesTheirId() {
        val local = ChatMessage("stable", "", Instant.EPOCH, MessageDirection.Outgoing)
        val echoed = local.copy(deliveryStatus = DeliveryStatus.Sent)
        assertEquals(listOf(echoed), reconcileMessages(listOf(echoed), listOf(local)))
    }

    @Test
    fun draftCarriesReplyTarget() {
        val draft = buildDraft(" reply ", emptyList(), emptyList(), "parent-id")
        assertEquals("reply", draft.text)
        assertEquals("parent-id", draft.replyToMessageId)
    }

    @Test
    fun editEligibilityHonorsDirectionTextAndWindow() {
        val now = Instant.parse("2026-01-01T00:10:00Z")
        val recent = ChatMessage("1", "hello", now.minusSeconds(60), MessageDirection.Outgoing)
        assertTrue(recent.canEdit(now, 120_000))
        assertFalse(recent.copy(direction = MessageDirection.Incoming).canEdit(now, 120_000))
        assertFalse(recent.copy(text = "").canEdit(now, 120_000))
        assertFalse(recent.canEdit(now.plusSeconds(121), 120_000))
    }
}

class DaySeparatorsTest {
    @Test
    fun labelsUseTodayYesterdayAndFormattedDates() {
        val calendar = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -5)
        val earlierThisYear = calendar.timeInMillis
        calendar.set(2020, Calendar.JANUARY, 3, 9, 0, 0)
        val olderYear = calendar.timeInMillis

        val now = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 18, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("Today", daySeparatorLabel(today, now))
        assertEquals("Yesterday", daySeparatorLabel(yesterday, now))
        assertEquals(
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(earlierThisYear)),
            daySeparatorLabel(earlierThisYear, now),
        )
        assertEquals(
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(olderYear)),
            daySeparatorLabel(olderYear, now),
        )
    }

    @Test
    fun transcriptInsertsDayPillsBetweenDays() {
        val calendar = Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val first = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val second = calendar.timeInMillis
        val messages = listOf(
            ChatMessage("a", "a", Instant.ofEpochMilli(first), MessageDirection.Incoming),
            ChatMessage("b", "b", Instant.ofEpochMilli(second), MessageDirection.Outgoing),
        )
        val items = buildTranscriptItems(messages, Calendar.getInstance().apply {
            set(2024, Calendar.JUNE, 16, 18, 0, 0)
        })
        assertEquals(4, items.size)
        assertTrue(items[0] is TranscriptItem.DaySeparator)
        assertTrue(items[1] is TranscriptItem.Message)
        assertTrue(items[2] is TranscriptItem.DaySeparator)
        assertTrue(items[3] is TranscriptItem.Message)

        // reverseLayout lists newest-first so index 0 sits at the visual bottom.
        val inverted = items.asReversed()
        assertTrue(inverted[0] is TranscriptItem.Message)
        assertEquals("b", (inverted[0] as TranscriptItem.Message).message.text)
        assertTrue(inverted.last() is TranscriptItem.DaySeparator)
    }
}
