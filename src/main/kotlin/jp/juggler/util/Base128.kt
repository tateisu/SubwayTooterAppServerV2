package jp.juggler.util

import java.io.ByteArrayOutputStream

@Suppress("unused")
fun String.decodeBase128(): ByteArray = ByteArrayOutputStream(this.length).also {
    var bits = 0
    var bitsUsed = 0
    for (c in this) {
        bits = bits.shl(7).or(c.code.and(0x7f))
        bitsUsed += 7
        if (bitsUsed >= 8) {
            val outByte = bits.shr(bitsUsed - 8).and(0xff)
            it.write(outByte)
            bitsUsed -= 8
        }
    }
    // bitsUsedに8未満のbitが残ることがあるが、末尾のパディングなので読み捨てる
}.toByteArray()

fun ByteArray.encodeBase128() = StringBuilder(this.size).also {
    var bits = 0
    var bitsUsed = 0
    for (inByte in this) {
        bits = bits.shl(8).or(inByte.toInt().and(255))
        bitsUsed += 8
        while (bitsUsed >= 7) {
            val outBits = bits.shr(bitsUsed - 7).and(0x7f)
            bitsUsed -= 7
            it.append(outBits.toChar())
        }
    }
    if (bitsUsed > 0) {
        val outBits = bits.shl(7 - bitsUsed).and(0x7f)
        it.append(outBits.toChar())
    }
}.toString()
