package app.burnout.mark

data class TestPattern(
    val backgroundColor: Int,
    val cursorColor: Int,
    val markColor: Int = 0xFFFFFFFF.toInt(),
) {
    companion object {
        fun forChannel(channel: Channel): TestPattern {
            val bg = when (channel) {
                Channel.R -> 0xFFFF0000.toInt()
                Channel.G -> 0xFF00FF00.toInt()
                Channel.B -> 0xFF0000FF.toInt()
                Channel.W -> 0xFFFFFFFF.toInt()
            }
            val cursor = (bg xor 0x00FFFFFF) or 0xFF000000.toInt()
            return TestPattern(bg, cursor)
        }
    }
}
