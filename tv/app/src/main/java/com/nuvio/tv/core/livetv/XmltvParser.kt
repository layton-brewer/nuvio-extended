package com.nuvio.tv.core.livetv

/**
 * Reads an XMLTV programme guide and keeps only the next few hours for each channel, so even a
 * very large guide costs little memory once parsed.
 */
object XmltvParser {

    private val PROGRAMME = Regex("""<programme\b([^>]*)>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""([A-Za-z_-]+)\s*=\s*"([^"]*)"""")
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val DESCRIPTION = Regex("""<desc\b[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)

    /** How far ahead the guide keeps programmes. */
    const val WINDOW_MS = 6L * 60 * 60 * 1000
    /** At most this many programmes per channel, so a guide with very short slots stays small. */
    const val MAX_PER_CHANNEL = 12
    const val MAX_DESCRIPTION = 240
    /** Descriptions are kept only for programmes starting this soon, to save memory on big guides. */
    private const val DESCRIPTION_AHEAD_MS = 90L * 60 * 1000

    /** Channel guide id (lower case) to what is on now and next at [nowMs]. */
    fun parseNowNext(xml: String, nowMs: Long): Map<String, LiveNowNext> =
        Reader(nowMs).also { it.feed(xml) }.result()

    /**
     * Reads a guide piece by piece, so a guide of hundreds of megabytes never has to sit in
     * memory. Pass [wanted] (lower-case guide ids) to keep only those channels.
     */
    class Reader(private val nowMs: Long, private val wanted: Set<String>? = null) {
        private val pending = StringBuilder()
        private val found = HashMap<String, MutableList<LiveProgramme>>()

        fun feed(chunk: String) {
            pending.append(chunk)
            var position = 0
            while (true) {
                val start = pending.indexOf(OPEN, position)
                if (start < 0) {
                    // Keep a few characters in case an opening tag is split across two pieces.
                    position = maxOf(position, pending.length - OPEN.length)
                    break
                }
                val end = pending.indexOf(CLOSE, start)
                if (end < 0) {
                    position = start
                    break
                }
                position = end + CLOSE.length
                PROGRAMME.find(pending.substring(start, position))?.let(::accept)
            }
            pending.deleteRange(0, position)
            // A broken guide with an element that never closes must not grow without limit.
            if (pending.length > MAX_PENDING) pending.clear()
        }

        fun result(): Map<String, LiveNowNext> =
            found.mapNotNull { (channel, programmes) -> LiveNowNext.of(programmes, nowMs)?.let { channel to it } }.toMap()

        private fun accept(match: MatchResult) {
            val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val channel = attributes["channel"]?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return
            if (wanted != null && channel !in wanted) return
            val start = parseTime(attributes["start"]) ?: return
            val stop = parseTime(attributes["stop"]) ?: return
            if (stop <= nowMs || start > nowMs + WINDOW_MS || stop <= start) return
            val body = match.groupValues[2]
            val title = TITLE.find(body)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
            val description = if (start > nowMs + DESCRIPTION_AHEAD_MS) {
                null
            } else {
                DESCRIPTION.find(body)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                    ?.take(MAX_DESCRIPTION)?.takeIf(String::isNotEmpty)
            }
            val programme = LiveProgramme(title = title, startMs = start, stopMs = stop, description = description)
            val list = found.getOrPut(channel) { ArrayList() }
            if (list.any { it.startMs == start }) return
            list += programme
            if (list.size > MAX_PER_CHANNEL) {
                // Keep the earliest ones: those are what the guide shows first.
                list.sortBy { it.startMs }
                list.removeAt(list.lastIndex)
            }
        }
    }

    private const val OPEN = "<programme"
    private const val CLOSE = "</programme>"
    private const val MAX_PENDING = 1_000_000

    /** XMLTV time: `YYYYMMDDhhmmss +hhmm`. Seconds and the offset are optional. */
    internal fun parseTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        val digits = text.takeWhile(Char::isDigit)
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val offsetText = text.substring(digits.length).trim()
        val offsetMinutes = if (offsetText.length >= 5 && (offsetText[0] == '+' || offsetText[0] == '-')) {
            val hours = offsetText.substring(1, 3).toIntOrNull() ?: return null
            val minutes = offsetText.substring(3, 5).toIntOrNull() ?: return null
            (hours * 60 + minutes) * if (offsetText[0] == '-') -1 else 1
        } else {
            0
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
        return (seconds - offsetMinutes * 60L) * 1000L
    }

    /** Days since 1970-01-01 for a calendar date (proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun decodeEntities(text: String): String =
        text.replace("<![CDATA[", "").replace("]]>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}
