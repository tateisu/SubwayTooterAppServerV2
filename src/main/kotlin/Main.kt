import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import db.Endpoint
import db.EndpointUsage
import db.LargeMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import jp.juggler.util.BinPackMap
import jp.juggler.util.encodeBase128
import jp.juggler.util.encodeBinPack
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import util.buildJsonObject
import util.decodeJsonObject
import util.decodeUTF8
import util.e
import util.gone
import util.i
import util.jsonApi
import util.jsonObjectOf
import util.notBlank
import util.notEmpty
import util.notZero
import util.respondError
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

// 暗号のデコードに必要なヘッダ。小文字化
val encryptionHeaders = arrayOf(
    "Content-Encoding",
    "Crypto-Key",
    "Encryption",
).map { it.lowercase() }.toSet()

private val log = LoggerFactory.getLogger("Main")

val config = Config()
// val verbose get() = config.verbose

val tables = arrayOf(
    Endpoint.Meta,
    EndpointUsage.Meta,
    LargeMessage.Meta,
)

val client = HttpClient(CIO) {
    install(Logging) {
        logger = Logger.DEFAULT
        level = LogLevel.INFO
    }
    install(HttpTimeout) {
        requestTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        connectTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
        socketTimeoutMillis = TimeUnit.SECONDS.toMillis(20)
    }
}

val sendFcm = SendFcm()
val sendUnifiedPush = SendUnifiedPush(client)

private fun createStatementsX(vararg tables: Table): List<String> {
    if (tables.isEmpty()) return emptyList()

    val toCreate = SchemaUtils.sortTablesByReferences(tables.toList())
    val alters = arrayListOf<String>()
    return toCreate.flatMap { table ->
        val (create, alter) = table.ddl.partition { it.startsWith("CREATE ") }
        val indicesDDL = table.indices.flatMap { SchemaUtils.createIndex(it) }
        alters += alter
        create + indicesDDL
    } + alters
}

fun main(args: Array<String>) {
    val pid = File("/proc/self").canonicalFile.name
    log.i("main start! pid=$pid, pwd=${File(".").canonicalFile}")

    config.parseArgs(args)

    config.pidFile.notBlank()?.let {
        File(it).writeText(pid.toString())
    }

    sendFcm.loadCredential(config.fcmCredentialPath)

    val dataSource = HikariConfig().apply {
        driverClassName = config.dbDriver
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword

    }.let { HikariDataSource(it) }

    transaction(Database.connect(dataSource)) {
        for (s in createStatementsX(tables = tables)) {
            if (s.isNotBlank()) log.i("SCHEMA: $s")
        }
        SchemaUtils.create(tables = tables)
    }

    val timeJob = launchTimerJob()

    val server = embeddedServer(
        Netty,
        host = config.listenHost,
        port = config.listenPort,
        module = Application::module
    ).start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.i("cancel timer job…")
        timeJob.cancel()
        log.i("stop http server…")
        server.stop(
            gracePeriodMillis = 166L,
            timeoutMillis = TimeUnit.SECONDS.toMillis(10),
        )
        log.i("join timer job…")
        runBlocking { timeJob.join() }
        log.i("shutdown complete.")
    })

    Thread.currentThread().join()
}

@OptIn(DelicateCoroutinesApi::class)
fun launchTimerJob() = GlobalScope.launch(Dispatchers.IO) {
    while (true) {
        try {
            delay(TimeUnit.MINUTES.toMillis(5))
            deleteOldEndpoints()
            deleteOldLargeMessage()
        } catch (ex: Throwable) {
            if (ex is CancellationException) break
            log.e(ex, "timerJob error")
        }
    }
}

suspend fun deleteOldEndpoints() {
    val usageAccess = EndpointUsage.Access()

    val oldIds = usageAccess.oldIds()

    Endpoint.Access().deleteIds(oldIds)
        .notZero()
        ?.let { log.i("endpointAccess.deleteIds: count=$it") }

    usageAccess.deleteIds(oldIds)
        .notZero()
        ?.let { log.i("usageAccess.deleteIds: count=$it") }
}

suspend fun deleteOldLargeMessage() {
    val expire = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
    LargeMessage.Access()
        .deleteOld(expire)
        .notZero()
        ?.let { log.i("deleteOldLargeMessage: count=$it") }
}

fun Application.module() {
    install(DoubleReceive) {
    }
    install(CallLogging) {
        level = Level.INFO
    }
    routing {
        get("/ping") {
            jsonApi {
                jsonObjectOf("ping" to "pong")
            }
        }

        delete("/endpoint/remove") {
            jsonApi {
                val query = call.request.queryParameters
                val upUrl = query["upUrl"]
                val fcmToken = query["fcmToken"]
                val hashId = query["hashId"]
                val dao = Endpoint.Access()
                val count = when {
                    !hashId.isNullOrEmpty() -> dao.deleteByHashId(hashId = hashId)
                    else -> dao.delete(upUrl = upUrl, fcmToken = fcmToken)
                }
                log.i("remove count=$count, upUrl=$upUrl, fcmToken=$fcmToken, hashId=$hashId")
                jsonObjectOf("count" to count)
            }
        }

        post("/endpoint/upsert") {
            jsonApi {
                val reqJson = call.receive<ByteArray>().decodeUTF8().decodeJsonObject()

                val upUrl = reqJson.string("upUrl")
                val fcmToken = reqJson.string("fcmToken")
                if ((upUrl == null).xor(fcmToken == null).not()) {
                    error("upUrl and fcmToken must specify one. reqJson=$reqJson")
                }

                val acctHashList = reqJson.jsonArray("acctHashList")
                    ?.stringList()
                if (acctHashList.isNullOrEmpty()) {
                    error("acctHashList is null or empty")
                }
                buildJsonObject {
                    val map = Endpoint.Access().upsert(
                        acctHashList = acctHashList,
                        upUrl = upUrl,
                        fcmToken = fcmToken
                    )
                    EndpointUsage.Access().updateUsage(map.values.toSet())
                    map.entries.forEach { e ->
                        put(e.key, e.value)
                        // map of acctHash to appServerHash
                        log.i("ah=>ash ${e.key}=>${e.value}")
                    }
                }
            }
        }

        post("/m/{...}") {

            fun String.parsePath() = buildMap {
                split("/").forEach { pair ->
                    val cols = pair.split("_", limit = 2)
                    cols.elementAtOrNull(0).notEmpty()?.let { k ->
                        put(k, cols.elementAtOrNull(1) ?: "")
                    }
                }
            }

            jsonApi {
                val params = call.request.uri.substring(3).parsePath()
                val appServerHash = params["a"]
                    ?: error("missing json parameter 'a'")
                val dao = Endpoint.Access()
                val endpoint = dao.find(appServerHash)
                    ?: "missing endpoint for this hash.".gone()

                val headerMap = BinPackMap().also { dst ->
                    for (e in call.request.headers.entries()) {
                        // HTTPヘッダのキーを小文字にする
                        val k = e.key.lowercase()
                        if (encryptionHeaders.contains(k)) {
                            e.value.firstOrNull()?.let {
                                dst.put(k, it)
                            }
                        }
                    }
                }

                val body = call.receive<ByteArray>()

                val longMessage = BinPackMap().apply {
                    put("a", endpoint.acctHash)
                    put("b", body)
                    put("c", endpoint.hashId)
                    put("h", headerMap)
                }.encodeBinPack()

                suspend fun data(ratio: Float) = when {
                    longMessage.size.toFloat().times(ratio) <= 4000f -> longMessage
                    else -> {
                        // 長いバイトデータはDBに保存してキーを送る
                        val uuid = LargeMessage.Access().create(longMessage)
                        BinPackMap().apply {
                            put("a", endpoint.acctHash)
                            put("c", endpoint.hashId)
                            put("l", uuid)
                        }.encodeBinPack()
                    }
                }

                val upUrl = endpoint.upUrl
                val fcmToken = endpoint.fcmToken

                when {
                    upUrl != null -> withContext(Dispatchers.IO) {
                        sendUnifiedPush.send(data(1f), upUrl)
                        EndpointUsage.Access().updateUsage1(appServerHash)
                        jsonObjectOf("result" to "sent to UnifiedPush endpoint.")
                    }

                    fcmToken != null -> withContext(Dispatchers.IO) {
                        // Base128の変換ロスが14%あるかも
                        sendFcm.send(fcmToken) { putData("d", data(1.142f).encodeBase128()) }
                        EndpointUsage.Access().updateUsage1(appServerHash)
                        jsonObjectOf("result" to "sent to FCM.")
                    }

                    else -> "missing redirect destination.".gone()
                }
            }
        }
        get("/l/{objectId}") {
            try {
                call.parameters["objectId"]
                    ?.let { LargeMessage.Access().find(it) }
                    ?.let {
                        call.respondBytes(
                            it.data,
                            contentType = ContentType.Application.OctetStream,
                            status = HttpStatusCode.OK
                        )
                        return@get
                    }
                "object id not match".respondError(call, status = HttpStatusCode.BadRequest)
            } catch (ex: Throwable) {
                log.e(ex, "/l failed.")
                "server side problem.".respondError(call)
            }
        }
    }
}
