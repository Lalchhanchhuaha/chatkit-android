package com.chatkit.compose

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal sealed interface TranscriptItem {
    data class DaySeparator(val id: String, val label: String) : TranscriptItem
    data class Message(val message: ChatMessage) : TranscriptItem
}

internal fun buildTranscriptItems(
    messages: List<ChatMessage>,
    calendar: Calendar = Calendar.getInstance(),
): List<TranscriptItem> {
    if (messages.isEmpty()) return emptyList()
    val items = ArrayList<TranscriptItem>(messages.size * 2)
    var lastDayKey: String? = null
    for (message in messages) {
        val dayKey = dayKey(message.timestampMillis, calendar)
        if (dayKey != lastDayKey) {
            items += TranscriptItem.DaySeparator(
                id = "day-$dayKey",
                label = daySeparatorLabel(message.timestampMillis, calendar),
            )
            lastDayKey = dayKey
        }
        items += TranscriptItem.Message(message)
    }
    return items
}

internal fun daySeparatorLabel(
    timestampMillis: Long,
    calendar: Calendar = Calendar.getInstance(),
): String {
    val messageCal = calendar.clone() as Calendar
    messageCal.timeInMillis = timestampMillis
    val today = calendar.clone() as Calendar
    val yesterday = calendar.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)

    return when {
        isSameDay(messageCal, today) -> "Today"
        isSameDay(messageCal, yesterday) -> "Yesterday"
        messageCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestampMillis))
        else ->
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestampMillis))
    }
}

private fun dayKey(timestampMillis: Long, calendar: Calendar): String {
    val cal = calendar.clone() as Calendar
    cal.timeInMillis = timestampMillis
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
    )
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
