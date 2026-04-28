package app.burnout.mark

class WearMask(val width: Int, val height: Int) {
    private val pixels = IntArray(width * height) { 0xFF000000.toInt() }

    fun getSeverity(x: Int, y: Int, channel: Channel): Severity {
        if (!inBounds(x, y) || channel == Channel.W) return Severity(0)
        val argb = pixels[y * width + x]
        val byteValue = when (channel) {
            Channel.R -> (argb shr 16) and 0xFF
            Channel.G -> (argb shr 8) and 0xFF
            Channel.B -> argb and 0xFF
            Channel.W -> 0
        }
        return Severity.fromPixelValue(byteValue)
    }

    fun mark(x: Int, y: Int, channel: Channel, severity: Severity) {
        require(channel.isMarkingChannel) { "$channel is not a marking channel" }
        if (!inBounds(x, y)) return
        val idx = y * width + x
        val current = pixels[idx]
        val newValue = severity.toPixelValue()
        pixels[idx] = when (channel) {
            Channel.R -> (current and 0xFF00FFFF.toInt()) or (newValue shl 16)
            Channel.G -> (current and 0xFFFF00FF.toInt()) or (newValue shl 8)
            Channel.B -> (current and 0xFFFFFF00.toInt()) or newValue
            Channel.W -> error("unreachable")
        }
    }

    /**
     * Mark with an exact 0..255 channel value (used by image-stamp's channel-bucketed
     * translation per spec §4.2 — preserves source intensity within the format).
     * Out-of-bounds pixels and value 0 are silently no-op.
     */
    fun markRaw(x: Int, y: Int, channel: Channel, value: Int) {
        require(channel.isMarkingChannel) { "$channel is not a marking channel" }
        if (!inBounds(x, y)) return
        val v = value.coerceIn(0, 255)
        if (v == 0) return  // 0 = no wear, skip per spec §4.2
        val idx = y * width + x
        val current = pixels[idx]
        pixels[idx] = when (channel) {
            Channel.R -> (current and 0xFF00FFFF.toInt()) or (v shl 16)
            Channel.G -> (current and 0xFFFF00FF.toInt()) or (v shl 8)
            Channel.B -> (current and 0xFFFFFF00.toInt()) or v
            Channel.W -> error("unreachable")
        }
    }

    fun cycleAtPixel(x: Int, y: Int, channel: Channel) {
        if (!channel.isMarkingChannel) return
        val current = getSeverity(x, y, channel)
        mark(x, y, channel, current.cycle())
    }

    fun clearChannel(channel: Channel) {
        require(channel.isMarkingChannel) { "$channel is not a marking channel" }
        val keepMask = when (channel) {
            Channel.R -> 0xFF00FFFF.toInt()
            Channel.G -> 0xFFFF00FF.toInt()
            Channel.B -> 0xFFFFFF00.toInt()
            Channel.W -> error("unreachable")
        }
        for (i in pixels.indices) pixels[i] = pixels[i] and keepMask
    }

    fun isMarked(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        return (pixels[y * width + x] and 0x00FFFFFF) != 0
    }

    fun toPixelArray(): IntArray = pixels.copyOf()

    /** Iterate (x, y) coordinates of pixels with non-zero severity in the given channel. */
    fun forEachMarked(channel: Channel, action: (Int, Int) -> Unit) {
        if (!channel.isMarkingChannel) return
        val shift = when (channel) {
            Channel.R -> 16
            Channel.G -> 8
            Channel.B -> 0
            Channel.W -> return
        }
        for (i in pixels.indices) {
            if ((pixels[i] shr shift) and 0xFF != 0) {
                action(i % width, i / width)
            }
        }
    }

    private fun inBounds(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height

    companion object {
        fun fromPixelArray(pixels: IntArray, width: Int, height: Int): WearMask {
            require(pixels.size == width * height) {
                "pixels.size=${pixels.size} != width*height=${width * height}"
            }
            val mask = WearMask(width, height)
            for (i in pixels.indices) {
                mask.pixels[i] = (pixels[i] and 0x00FFFFFF) or 0xFF000000.toInt()
            }
            return mask
        }
    }
}
