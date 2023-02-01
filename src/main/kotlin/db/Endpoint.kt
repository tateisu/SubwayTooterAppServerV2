package db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import util.digestSHA256
import util.encodeBase64UrlSafe
import util.encodeUTF8

/**
 * 中継先( endpointUrl または fcmToken )の情報
 */
@Serializable
data class Endpoint(
    val hashId: String,
    val acctHash: String,
    val upUrl: String?,
    val fcmToken: String?,
) {
    object Meta : Table("endpoints") {
        val acctHash = text("acct_hash")
        val hashId = text("hash_id")
        val upUrl = text("up_url").nullable()
        val fcmToken = text("fcm_token").nullable()

        override val primaryKey = PrimaryKey(hashId)

        init {
            index(columns = arrayOf(upUrl, fcmToken))
        }
    }

    interface Access {
        suspend fun find(hashId: String): Endpoint?

        suspend fun delete(
            upUrl: String?,
            fcmToken: String?,
        ): Int

        suspend fun upsert(
            acctHashList: List<String>,
            upUrl: String?,
            fcmToken: String?,
        ): Map<String, String>

        suspend fun deleteIds(oldIds: List<String>): Int
    }

    class AccessImpl : Access {
        private fun resultRowToArticle(row: ResultRow) = Endpoint(
            hashId = row[Meta.hashId],
            acctHash = row[Meta.acctHash],
            upUrl = row[Meta.upUrl],
            fcmToken = row[Meta.fcmToken],
        )

        override suspend fun delete(
            upUrl: String?,
            fcmToken: String?,
        ): Int = dbQuery {
            Meta.deleteWhere {
                (Meta.upUrl eq upUrl)
                    .and(Meta.fcmToken eq fcmToken)
            }
        }

        override suspend fun find(hashId: String): Endpoint? = dbQuery {
            Meta.select { Meta.hashId eq hashId }
                .map(::resultRowToArticle)
                .singleOrNull()
        }

        /**
         * 複数のEndpoint登録を更新する
         *
         * @return map of acctHash to appServerHash
         *
         */
        override suspend fun upsert(
            acctHashList: List<String>,
            upUrl: String?,
            fcmToken: String?,
        ): Map<String, String> = dbQuery {
            buildMap {
                for (acctHash in acctHashList) {
                    val hashId = "$acctHash,${upUrl ?: ""},${fcmToken ?: ""}"
                        .encodeUTF8().digestSHA256().encodeBase64UrlSafe()

                    val row = Meta.select {
                        Meta.hashId eq hashId
                    }.singleOrNull()

                    if (row == null) {
                        Meta.insert {
                            it[Meta.acctHash] = acctHash
                            it[Meta.upUrl] = upUrl
                            it[Meta.fcmToken] = fcmToken
                            it[Meta.hashId] = hashId
                        }.resultedValues
                            ?.singleOrNull()
                            ?.let(::resultRowToArticle)
                            ?: error("insert failed.")
                    }
                    put(acctHash, hashId)
                }
            }
        }

        override suspend fun deleteIds(oldIds: List<String>) = dbQuery {
            Meta.deleteWhere { hashId.inList(oldIds) }
        }
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is Endpoint -> false
        else -> this.hashId == other.hashId
    }

    override fun hashCode() = hashId.hashCode()
}
