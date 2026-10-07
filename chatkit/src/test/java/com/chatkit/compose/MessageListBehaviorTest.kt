package com.chatkit.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageListBehaviorTest {
    @Test
    fun incomingMessageFollowsOnlyNearNewestEdge() {
        assertTrue(
            shouldScrollToNewestOnNewMessage(
                isViewingNewest = true,
                hasOutgoingMessage = false,
                isScrollInProgress = false,
            ),
        )
        assertFalse(
            shouldScrollToNewestOnNewMessage(
                isViewingNewest = false,
                hasOutgoingMessage = false,
                isScrollInProgress = false,
            ),
        )
    }

    @Test
    fun outgoingMessageFollowsFromHistoryButNeverInterruptsGesture() {
        assertTrue(
            shouldScrollToNewestOnNewMessage(
                isViewingNewest = false,
                hasOutgoingMessage = true,
                isScrollInProgress = false,
            ),
        )
        assertFalse(
            shouldScrollToNewestOnNewMessage(
                isViewingNewest = true,
                hasOutgoingMessage = true,
                isScrollInProgress = true,
                newestRowFullyVisible = true,
            ),
        )
    }

    @Test
    fun followContinuesWhenNewestRowNotYetVisibleDuringScroll() {
        // Keyed reverseLayout insert leaves index > 0; attachment transfer updates
        // must still be allowed to finish scrolling to the new bottom row.
        assertTrue(
            shouldScrollToNewestOnNewMessage(
                isViewingNewest = true,
                hasOutgoingMessage = false,
                isScrollInProgress = true,
                newestRowFullyVisible = false,
            ),
        )
    }

    @Test
    fun paginationTriggersInsideOldestEdgeThreshold() {
        assertTrue(shouldLoadPreviousMessages(50, 49, 3))
        assertTrue(shouldLoadPreviousMessages(50, 47, 3))
        assertFalse(shouldLoadPreviousMessages(50, 46, 3))
        assertFalse(shouldLoadPreviousMessages(3, 2, 3))
        assertFalse(shouldLoadPreviousMessages(0, -1, 3))
    }
}
