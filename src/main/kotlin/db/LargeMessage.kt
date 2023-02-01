package db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.util.*

/**
 * プッシュサーバで中継できない大きなデータ
 */
@Serializable
data class LargeMessage(
    val uuid: String,
    val timeCreate: Long,
    val data: ByteArray,
) {
    object Meta : Table("large_message") {
        val uuid = text("uuid")
        val timeCreate = long("time_create").index()
        val data = blob("data")

        override val primaryKey = PrimaryKey(uuid)
    }

    interface Access {
        suspend fun create(bytes: ByteArray): String

        suspend fun find(uuid: String): LargeMessage?

        suspend fun deleteOld(expire: Long): Int
    }

    class AccessImpl : Access {
        override suspend fun create(bytes: ByteArray) = dbQuery {
            UUID.randomUUID().toString().also { newUuid ->
                Meta.insert {
                    it[uuid] = newUuid
                    it[timeCreate] = System.currentTimeMillis()
                    it[data] = ExposedBlob(bytes)
                }
            }
        }

        private fun ResultRow.toLargeMessage() =
            LargeMessage(
                uuid = this[Meta.uuid],
                timeCreate = this[Meta.timeCreate],
                data = this[Meta.data].bytes,
            )

        override suspend fun find(uuid: String) = dbQuery {
            Meta.select { Meta.uuid.eq(uuid) }
                .singleOrNull()?.toLargeMessage()
        }

        override suspend fun deleteOld(expire: Long) = dbQuery {
            Meta.deleteWhere {
                timeCreate.less(expire)
            }
        }
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is LargeMessage -> false
        else -> this.uuid == other.uuid
    }

    override fun hashCode() = uuid.hashCode()
}
