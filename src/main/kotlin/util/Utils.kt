@file:Suppress("unused")

package util

import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

fun Throwable.withCaption(caption: String? = null) = when {
    caption.isNullOrBlank() -> "${javaClass.simpleName} $message"
    else -> "$caption : ${javaClass.simpleName} $message"
}

fun String?.isTruth() = when (this?.lowercase()) {
    null, "", "0", "false", "off", "none", "no" -> false
    else -> true
}

// Android style log shorthand
fun Logger.i(msg: String) = info(msg)
fun Logger.e(msg: String) = error(msg)
fun Logger.w(msg: String) = warn(msg)
fun Logger.e(ex: Throwable, msg: String) = error(msg, ex)
fun Logger.w(ex: Throwable, msg: String) = warn(msg, ex)

inline fun <reified T : Any> Any.castNotNull() = (this as T)
inline fun <reified T : Any> Any?.cast() = (this as? T)

fun <T : List<*>> T?.notEmpty() = if (this.isNullOrEmpty()) null else this

private val log = LoggerFactory.getLogger("Utils")

fun String.ellipsize(limit: Int = 128) = when {
    this.length <= limit -> this
    else -> "${substring(0, limit - 1)}…"
}

fun String.encodeUTF8() = toByteArray(StandardCharsets.UTF_8)

fun ByteArray.decodeUTF8() = toString(StandardCharsets.UTF_8)

fun ByteArray.encodeBase64UrlSafe(): String =
    Base64.encodeBase64URLSafeString(this)

fun String.decodeBase64(): ByteArray =
    Base64.decodeBase64(this)

fun ByteArray.digestSHA256(): ByteArray =
    DigestUtils.sha256(this)

fun <T : CharSequence> T?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : CharSequence> T?.notBlank() = if (this.isNullOrBlank()) null else this

fun Int?.notZero() = if (this == null || this == 0) null else this

