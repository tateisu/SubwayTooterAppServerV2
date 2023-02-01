package db

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import util.i
import java.util.concurrent.TimeUnit
import kotlin.math.min

private val log = LoggerFactory.getLogger("EndpointUsage")

/**
 * Endpointの仕様状況
 */
@Serializable
data class EndpointUsage(
    val hashId: String,
    val timeUsed: Long,
) {
    object Meta : Table("endpoint_usages") {
        val hashId = text("hash_id")
        val timeUsed = long("time_used").index()

        override val primaryKey = PrimaryKey(hashId)
    }

    interface Access {
        suspend fun updateUsage1(id: String)

        suspend fun updateUsage(idSet: Set<String>)
        suspend fun oldIds(): List<String>
        suspend fun deleteIds(oldIds: List<String>): Int
    }

    class AccessImpl : Access {
        override suspend fun updateUsage1(id: String) {
            dbQuery {
                val now = System.currentTimeMillis()
                val affectedRows = Meta.update(
                    where = { Meta.hashId eq id },
                    body = { it[timeUsed] = now }
                )
                if (affectedRows == 0) {
                    Meta.insertIgnore {
                        it[hashId] = id
                        it[timeUsed] = now
                    }
                }
            }
        }

        override suspend fun updateUsage(idSet: Set<String>) {
            val stepMax = 1000
            val idList = idSet.toList()
            val end = idSet.size
            val now = System.currentTimeMillis()
            for (i in 0 until end step stepMax) {
                val step = min(stepMax, end - i)
                log.i("updateUsage pos=$i/$end step=$step")
                val subList = idList.subList(i, i + step)
                dbQuery {
                    val existsList = Meta.select { Meta.hashId inList (subList) }.map { it[Meta.hashId] }
                    val existsSet = existsList.toSet()
                    val notExists = idList.filterNot { existsSet.contains(it) }

                    // 既存のレコードを更新
                    Meta.update(
                        { Meta.hashId inList (existsList) }
                    ) {
                        it[timeUsed] = now
                    }

                    // 既存のレコードにないIDのリスト
                    for (id in notExists) {
                        if (existsSet.contains(id)) continue
                        Meta.insertIgnore {
                            it[hashId] = id
                            it[timeUsed] = now
                        }
                    }
                }
            }
        }

        override suspend fun oldIds(): List<String> =
            dbQuery {
                val expire = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
                Meta.select { Meta.timeUsed less expire }
                    .limit(1000)
                    .map { it[Meta.hashId] }
            }

        override suspend fun deleteIds(oldIds: List<String>) = dbQuery {
            Meta.deleteWhere { hashId.inList(oldIds) }
        }
    }
}
