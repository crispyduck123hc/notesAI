package com.example.notesai.ui.editor

/**
 * Filters one AI reply against the note it was asked about, so an echoing model cannot
 * duplicate the note's own text into it.
 *
 * The architectural problem this solves: prompting is not a guarantee. Even with an explicit
 * "do not repeat the note's existing text", small models sometimes open their reply by
 * repeating the note (title and content) before answering — and every reply is *inserted*
 * into that same note, so the echo becomes a duplication. Detection at the model's mouth is
 * unreliable; detection at the insertion point is deterministic.
 *
 * Mechanism: chunks are held while they remain a prefix of the note's text, and released once
 * the reply diverges. On divergence the matched prefix length decides:
 *
 * - a **short** match is coincidence (a legitimate reply that happens to open with the same
 *   words — "fix the typos" producing a lightly-corrected copy), so the held text is flushed
 *   and the reply inserts in full;
 * - a **long** match — the bulk of the note echoed back — is the duplication failure, so only
 *   the text *after* the echo is inserted;
 * - a reply that never diverges (pure echo, or an echo that stops mid-note) inserts nothing.
 *
 * Comparison is against the note text captured when the run started, not the live field: the
 * field changes as chunks are inserted, and the model echoes what it was *sent*.
 *
 * Limitation, stated on purpose: this catches verbatim echoes, not paraphrases. A model that
 * rewrites the note in its own words is not detectable as duplication by any prefix rule —
 * that failure remains a prompt problem, and is why [stripEchoedNote] exists below.
 */
internal class EchoGuard(
    private val noteText: String,
) {
    private val note = noteText.trimEnd('\n')
    private val held = StringBuilder()
    private var decided = false

    /**
     * Feeds one streamed chunk; returns the text to insert now (possibly empty).
     *
     * Three exits, each a distinct verdict: still undecided (hold), an echo (strip), or a
     * legitimate reply (flush whole). (detekt: ReturnCount)
     */
    @Suppress("ReturnCount")
    fun feed(chunk: String): String {
        if (decided) return chunk
        held.append(chunk)
        val reply = held.toString()
        val matchLength = reply.commonPrefixWith(note).length
        val isStillPrefix = matchLength == reply.length
        if (isStillPrefix) {
            // Undecided: the reply so far *is* the note's opening. Hold it.
            return ""
        }
        decided = true
        val heldOut = held.toString()
        held.clear()
        return if (matchLength >= echoThreshold()) {
            // The blank lines an echo leaves between itself and the answer are formatting of the
            // echo, not of the answer.
            heldOut.substring(matchLength).trimStart('\n')
        } else {
            heldOut
        }
    }

    /** Call when the stream ends; returns anything still held that should be inserted. */
    fun finish(): String {
        val heldOut = held.toString()
        held.clear()
        if (decided || heldOut.isEmpty()) return heldOut
        // The stream ended with the reply still matching the note: a pure or partial echo.
        val matchLength = heldOut.commonPrefixWith(note).length
        return if (matchLength >= echoThreshold()) "" else heldOut
    }

    /** A match counts as an echo only when it covers a large share of the note being echoed. */
    private fun echoThreshold(): Int = maxOf(4, note.length * 2 / 5)
}

/**
 * The second, architectural layer: a **whitespace-normalized containment strip** over the text
 * that was actually inserted.
 *
 * The streaming guard above only catches echoes that open the reply verbatim. Models echo in
 * other shapes — a preamble first ("Here is the note:"), whitespace variations around the note,
 * an echo that dodges the divergence threshold — and the live guard either flushes or holds
 * nothing in those cases. But the app knows *exactly* which range it inserted, so after the
 * stream ends the inserted text can be compared with the note as a whole: if the note (with
 * whitespace collapsed) appears inside the inserted text (with whitespace collapsed), the echoed
 * block is removed from the note with one edit. The reader may see the echo flash briefly; the
 * committed note is clean.
 *
 * Returns the cleaned replacement for the inserted range, or null when nothing echoed.
 *
 * Three exits, each a distinct verdict: too-small note, no containment, or a stripped result.
 * (detekt: ReturnCount)
 */
@Suppress("ReturnCount")
internal fun stripEchoedNote(
    inserted: String,
    noteText: String,
): String? {
    val insertedNorm = normalizeForEcho(inserted)
    val noteNorm = normalizeForEcho(noteText)
    // Tiny notes are too easy to match by accident: a two-word note is contained in almost any
    // reply about it.
    if (noteNorm.length < MIN_CONTAINED_NOTE_LENGTH) return null
    val at = insertedNorm.indexOf(noteNorm)
    if (at == -1) return null

    // Map the matched normalized region back to the inserted text's own coordinates.
    val map = originalPositions(inserted)
    val start = map[at]
    val end = map[at + noteNorm.length - 1] + 1
    // The seam is re-joined deliberately: the echo's own boundary newlines belong to the echo,
    // and what remains either side of it is separated by one blank line.
    val head = inserted.substring(0, start).trimEnd('\n')
    val tail = inserted.substring(end).trimStart('\n')
    return when {
        head.isEmpty() -> tail
        tail.isEmpty() -> head
        else -> "$head\n\n$tail"
    }
}

/** Whitespace runs collapse to one space; the edges are trimmed. */
private fun normalizeForEcho(text: String): String =
    buildString {
        var lastWasSpace = true
        for (c in text) {
            if (c.isWhitespace()) {
                if (!lastWasSpace) {
                    append(' ')
                    lastWasSpace = true
                }
            } else {
                append(c)
                lastWasSpace = false
            }
        }
    }.trim()

/**
 * For each character of [text], the index it occupies in the *normalized* form — the inverse of
 * [normalizeForEcho], so a match in normalized coordinates can be cut out of the original.
 */
private fun originalPositions(text: String): List<Int> {
    val out = mutableListOf<Int>()
    var i = 0
    // Leading whitespace is trimmed by the normalizer, so it maps to nothing.
    while (i < text.length && text[i].isWhitespace()) i++
    while (i < text.length) {
        if (!text[i].isWhitespace()) {
            out.add(i)
            i++
            continue
        }
        // A whitespace run collapses to one space, which maps to the run's first index — and
        // only if content follows, mirroring the normalizer's trailing trim.
        val runStart = i
        while (i < text.length && text[i].isWhitespace()) i++
        if (i < text.length) out.add(runStart)
    }
    return out
}

private const val MIN_CONTAINED_NOTE_LENGTH = 16
