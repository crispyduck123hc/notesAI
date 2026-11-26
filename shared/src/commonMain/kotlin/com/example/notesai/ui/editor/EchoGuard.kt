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
 * that failure remains a prompt problem.
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
