import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import util.gone
import util.notBlank
import java.io.File
import java.io.FileInputStream

// implementation "com.google.firebase:firebase-admin:8.2.0"
class SendFcm {

    private val fcm by lazy {
        FirebaseMessaging.getInstance()
    }

    fun loadCredential(fcmCredentialPath: String) {
        fcmCredentialPath.notBlank()
            ?: error("missing fcmCredentialPath. please check config file.")
        FileInputStream(File(fcmCredentialPath))
            .use { GoogleCredentials.fromStream(it) }
            .let { FirebaseOptions.builder().setCredentials(it).build() }
            .let { FirebaseApp.initializeApp(it) }
    }

    /**
     * @return FCM message id
     */
    suspend fun send(
        fcmDeviceToken: String,
        block: suspend Message.Builder.() -> Unit,
    ): String = try {
        Message.builder().apply {
            setToken(fcmDeviceToken)
            block()
        }.build().let { fcm.send(it) }
    } catch (ex: FirebaseMessagingException) {
        if (ex.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
            "fcmDeviceToken unregistered.".gone()
        } else {
            throw ex
        }
    }
}
