package app.burnout.mark

@JvmInline
value class Severity(private val raw: Int) {
    val level: Int get() = raw.coerceIn(0, 4)

    /**
     * Top-of-bucket pixel value for this severity (spec §4.3):
     *   sev 0 -> 0   (no wear)
     *   sev 1 -> 63  (top of bucket 1: values 1-63)
     *   sev 2 -> 127 (top of bucket 2: values 64-127)
     *   sev 3 -> 191 (top of bucket 3: values 128-191)
     *   sev 4 -> 255 (top of bucket 4: values 192-255)
     */
    fun toPixelValue(): Int = when (level) {
        0 -> 0
        1 -> 63
        2 -> 127
        3 -> 191
        4 -> 255
        else -> error("unreachable")
    }

    fun cycle(): Severity = Severity(if (level == 4) 0 else level + 1)

    companion object {
        /**
         * Bucket assignment per spec §4.3 (the TABLE in §4.2 is authoritative; the
         * spec's inverse formula `ceil(value/64)` is a typo — the right math is
         * `(value/64) + 1` for value > 0, capped at 4):
         *   value == 0      -> sev 0
         *   1 <= value <= 63   -> sev 1
         *   64 <= value <= 127 -> sev 2
         *   128 <= value <= 191 -> sev 3
         *   192 <= value <= 255 -> sev 4
         */
        fun fromPixelValue(pixel: Int): Severity {
            if (pixel <= 0) return Severity(0)
            val v = pixel.coerceIn(0, 255)
            return Severity(((v / 64) + 1).coerceAtMost(4))
        }
    }
}

enum class Channel(val isMarkingChannel: Boolean) {
    R(isMarkingChannel = true),
    G(isMarkingChannel = true),
    B(isMarkingChannel = true),
    W(isMarkingChannel = false);

    fun cycle(): Channel = entries[(ordinal + 1) % entries.size]
}
