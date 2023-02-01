import util.isTruth
import java.io.File
import java.nio.charset.StandardCharsets

@Suppress("MemberVisibilityCanBePrivate")
class Config {

    var verbose = false

    var pidFile = "" // ""./pid"

    var listenHost = "0.0.0.0"
    var listenPort = 8080

    var dbUrl = "jdbc:h2:file:./database"
    var dbDriver = "org.h2.Driver"
    var dbUser = ""
    var dbPassword = ""

    var fcmCredentialPath = ""

    private fun parseOptionValue(k: String, v: String) {
        when (k) {
            "verbose" -> verbose = v.isTruth()
            "host" -> listenHost = v
            "port" -> listenPort = v.toIntOrNull()?.takeIf { it in 1 until 65536 }
                ?: error("incorrect listenPort [$v]")

            "dbUrl" -> dbUrl = v
            "dbDriver" -> dbDriver = v
            "dbUser" -> dbUser = v
            "dbPassword" -> dbPassword = v

            "fcmCredentialPath" -> fcmCredentialPath = v

            else -> error("unknown key: $k")
        }
    }

    private fun parseConfigFile(filePath: String) {
        val reLineFeed = """[\x0d\x0a]+\z""".toRegex()
        val reComment = """#.*""".toRegex()
        var hasError = false
        File(filePath).readBytes()
            .toString(StandardCharsets.UTF_8)
            .split("\n")
            .forEachIndexed { rawIndex, rawLine ->
                val lineNum = 1 + rawIndex
                try {
                    rawLine.replace(reLineFeed, "")
                        .replace(reComment, "")
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            val cols = it.split("=", limit = 2)
                            parseOptionValue(
                                cols[0].trim(),
                                (cols.elementAtOrNull(1) ?: cols[0]).trim()
                            )
                        }
                } catch (ex: Throwable) {
                    hasError = true
                    println("$filePath $lineNum : ${ex.javaClass.simpleName} ${ex.message}")
                }
            }
        if (hasError) error("$filePath has error(s).")
    }

    /**
     * コマンドライン引数を読み、このクラスのプロパティを更新する。
     * @return オプションを除去した後の引数リスト
     */
    fun parseArgs(args: Array<out String>): List<String> {
        var configFile: String? = null
        val remainArgs = buildList {
            var i = 0
            while (i < args.size) {
                var arg = args[i++]
                if (arg.elementAtOrNull(0) != '-') {
                    add(arg)
                } else if (arg == "--") {
                    addAll(args.slice(i until args.size))
                } else {
                    val cols = arg.split("=", limit = 2)
                    val valueAfterEqual = cols.elementAtOrNull(1)
                    fun getValue() =
                        valueAfterEqual
                            ?: args.elementAtOrNull(i++)
                            ?: error("missing value for option $arg")
                    arg = cols[0]
                    when (arg) {
                        "-v", "--verbose" -> verbose = true
                        "-c", "--config" -> configFile = getValue()
                        else -> error("unknown option $arg.")
                    }
                }
            }
        }
        configFile?.let { parseConfigFile(it) }
        return remainArgs
    }
}
