import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import util.gone

class SendUnifiedPush(
    private val client: HttpClient,
) {
    suspend fun send(newBody: ByteArray?, upUrl: String) {
        val response = client.post(upUrl) { setBody(newBody) }
        when {
            response.status.isSuccess() -> Unit

            response.status.value in 400 until 500 ->
                "push server returns permanent error ${response.status}".gone()

            else -> error("temporary error? ${response.status} $upUrl")
        }
    }
}
