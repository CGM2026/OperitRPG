package com.ai.assistance.operit.rpg.data

import android.content.Context
import androidx.room.*
import com.ai.assistance.operit.rpg.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Entity(tableName = "rpg_campaign")
data class RpgCampaignEntity(@PrimaryKey val id: String, val title: String, val updatedAt: Long, val revision: Long, val recordJson: String)

data class RpgCampaignHeader(val id: String, val title: String, val updatedAt: Long, val revision: Long)

@Dao
interface RpgCampaignDao {
    @Query("SELECT id,title,updatedAt,revision FROM rpg_campaign ORDER BY updatedAt DESC,id LIMIT :limit OFFSET :offset")
    suspend fun headers(limit: Int = 50, offset: Int = 0): List<RpgCampaignHeader>
    @Query("SELECT * FROM rpg_campaign ORDER BY updatedAt DESC")
    suspend fun all(): List<RpgCampaignEntity>
    @Query("SELECT * FROM rpg_campaign WHERE id = :id")
    suspend fun get(id: String): RpgCampaignEntity?
    @Query("SELECT id FROM rpg_campaign ORDER BY updatedAt DESC")
    fun changes(): Flow<List<String>>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RpgCampaignEntity)
    @Update
    suspend fun update(entity: RpgCampaignEntity)
}

@Database(entities = [RpgCampaignEntity::class], version = 1, exportSchema = true)
abstract class RpgDatabase : RoomDatabase() {
    abstract fun campaigns(): RpgCampaignDao
    companion object {
        @Volatile private var instance: RpgDatabase? = null
        fun get(context: Context): RpgDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, RpgDatabase::class.java, "rpg-v1.db")
                .build().also { instance = it }
        }
    }
}

/** The entire aggregate is decoded and replaced under Room's write transaction. */
class RoomGameStore(private val database: RpgDatabase) : GameStore {
    val changes = database.campaigns().changes()
    override suspend fun create(record: CampaignRecord) = database.withTransaction {
        validateRecord(record)
        database.campaigns().insert(record.entity())
    }
    override suspend fun list(): List<CampaignRecord> = database.campaigns().all().map { RpgJson.instance.decodeFromString(it.recordJson) }
    override suspend fun <T> transaction(campaignId: String, block: (CampaignRecord) -> T): T = database.withTransaction {
        val previous = database.campaigns().get(campaignId) ?: throw NoSuchElementException("战役不存在")
        val record = RpgJson.instance.decodeFromString<CampaignRecord>(previous.recordJson)
        val result = block(record)
        validateRecord(record)
        val next = record.entity()
        // Read-only projections must not invalidate the observation flow endlessly.
        if (next != previous) database.campaigns().update(next)
        result
    }
    private fun CampaignRecord.entity() = RpgCampaignEntity(id, title, updatedAt, state.revision, RpgJson.instance.encodeToString(this))
}

internal fun validateRecord(r: CampaignRecord) {
    RainyDock.validate(r.state)
    require(r.id.isNotBlank() && r.title.isNotBlank() && r.title.length <= 100)
    require(r.scenarioId == "rainy-dock-v1" && r.scenarioVersion == "1.0.0" && r.scenarioHash == RainyDock.hash && r.rulesVersion == "prototype-d100-v1")
    require(r.state.hp in 0..r.state.maxHp && r.state.maxHp == 6 && r.state.tick >= 0)
    require(r.state.inventory.values.all { it >= 0 })
    require(r.turns.map { it.id }.distinct().size == r.turns.size)
    require(r.turns.map { it.clientMessageId }.distinct().size == r.turns.size)
    require(r.turns.map { it.ordinal } == (1..r.turns.size).toList())
    require(r.turns.all { it.campaignId == r.id })
    require(r.turns.count { it.stage !in setOf(TurnStage.COMPLETE, TurnStage.CANCELLED) } <= 1)
    require(r.events.map { it.id }.distinct().size == r.events.size)
    require(r.events.map { it.sequence } == (1L..r.events.size.toLong()).toList())
    require(r.events.all { event -> event.campaignId == r.id && r.turns.any { it.id == event.turnId } })
}
