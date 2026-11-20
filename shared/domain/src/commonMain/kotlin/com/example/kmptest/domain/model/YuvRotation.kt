package com.example.kmptest.domain.model

/** Turns a frame upright: [degrees] (0 / 90 / 180 / 270) is the clockwise rotation needed to display it. */
fun YuvFrame.rotated(degrees: Int): YuvFrame {
    if (degrees == 0) return this
    val frame = this
    val w = frame.width
    val h = frame.height
    val sideways = degrees == 90 || degrees == 270
    val ow = if (sideways) h else w
    val oh = if (sideways) w else h
    val out = ByteArray(ow * oh * 3 / 2)
    var srcOff = 0
    var dstOff = 0
    for (p in 0 until 3) {
        val pw = if (p == 0) w else w / 2
        val ph = if (p == 0) h else h / 2
        val dw = if (sideways) ph else pw
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val di = when (degrees) {
                    90 -> x * dw + (ph - 1 - y)
                    270 -> (pw - 1 - x) * dw + y
                    else -> (ph - 1 - y) * dw + (pw - 1 - x)
                }
                out[dstOff + di] = frame.data[srcOff + y * pw + x]
            }
        }
        srcOff += pw * ph
        dstOff += pw * ph
    }
    return YuvFrame(ow, oh, out)
}
