package util

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("JsonApi")
suspend fun JsonObject.respond(
    call: ApplicationCall,
    status: HttpStatusCode = HttpStatusCode.OK,
) = call.respondText(
    contentType = ContentType.Application.Json,
    status = status,
    text = this.toString()
)

suspend fun String.respondError(
    call: ApplicationCall,
    status: HttpStatusCode = HttpStatusCode.InternalServerError,
) = jsonObjectOf("error" to this).respond(call, status)

/**
 * エラー応答を投げることのできる例外
 */
class RespondError(
    message: String,
    ex: Throwable? = null,
    val status:HttpStatusCode = HttpStatusCode.InternalServerError,
) : IllegalStateException(message, ex)

fun String.gone(): Nothing {
    throw RespondError(this,status = HttpStatusCode.Gone)
}

suspend fun PipelineContext<*, ApplicationCall>.jsonApi(
    block: suspend () -> JsonObject,
) {
    try {
        block().respond(call)
    } catch (ex: Throwable) {
        if (ex is RespondError) {
            log.e("util.respondError ${ex.status} ${ex.message}")
            (ex.message ?: "(null)").respondError(call,status = ex.status )
        } else {
            log.e(ex, "${call.request.uri} failed.")
            val message = ex.message
            if (!message.isNullOrBlank() && ex.cause == null && ex is IllegalStateException) {
                message.respondError(call)
            } else {
                ex.withCaption("API internal error.").respondError(call)
            }
        }
    }
}
