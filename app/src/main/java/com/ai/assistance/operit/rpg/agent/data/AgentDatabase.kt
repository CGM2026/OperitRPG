package com.ai.assistance.operit.rpg.agent.data

import android.content.Context
import androidx.room.*
import kotlinx.serialization.Serializable

@Entity(tableName = "campaign")
data class CampaignRow(@PrimaryKey val id: String, val title: String, val moduleId: String, val moduleVersion: String, val moduleHash: String, val rulesJson: String, val revision: Long, val lastEventSeq: Long, val locationId: String, val resourcesJson: String, val skillsJson: String, val gameTime: Long, val allowGeneration: Boolean, val modelFingerprint: String, @ColumnInfo(defaultValue = "") val narratorFingerprint: String, val updatedAt: Long, val pendingTurnId: String?, val nextTurnOrdinal:Long, val d20Json:String?, @ColumnInfo(defaultValue = "0") val contentLevel:Int, @ColumnInfo(defaultValue = "0") val sceneHold:Boolean, val flagsJson:String?, val scheduleJson:String?)
@Entity(tableName = "campaign_group")
data class CampaignGroupRow(@PrimaryKey val id: String, val name: String, val sortKey: Long, val collapsed: Boolean)
@Entity(tableName = "campaign_meta", indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = CampaignRow::class, parentColumns = ["id"], childColumns = ["campaignId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = CampaignGroupRow::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.SET_NULL)])
data class CampaignMetaRow(@PrimaryKey val campaignId: String, val groupId: String?, val pinned: Boolean, val hidden: Boolean, val displayTitle: String?, val avatarGlyph: String?, val avatarPair: String?, val lastPlayedAt: Long, @ColumnInfo(defaultValue = "0") val finished: Boolean)
@Entity(tableName = "turn", indices = [Index(value=["campaignId", "clientMessageId"], unique=true), Index(value=["campaignId", "ordinal"], unique=true)], foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class TurnRow(@PrimaryKey val id: String, val campaignId: String, val clientMessageId: String, val ordinal: Long, val phase: String, val json: String)
@Entity(tableName = "event", primaryKeys=["campaignId", "seq"], indices=[Index("turnId", unique=true)], foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE), ForeignKey(entity=TurnRow::class,parentColumns=["id"],childColumns=["turnId"])])
data class EventRow(val campaignId: String, val seq: Long, val turnId: String, val eventVersion: Int, val json: String)
@Entity(tableName="model_attempt",indices=[Index("turnId")],foreignKeys=[ForeignKey(entity=TurnRow::class,parentColumns=["id"],childColumns=["turnId"],onDelete=ForeignKey.CASCADE)])
data class AttemptRow(@PrimaryKey val id:String,val turnId:String,val ordinal:Int,val purpose:String,val fingerprint:String,val status:String,val inputTokens:Int?,val outputTokens:Int?)
@Entity(tableName="tool_result",primaryKeys=["turnId","invocationId"],foreignKeys=[ForeignKey(entity=TurnRow::class,parentColumns=["id"],childColumns=["turnId"],onDelete=ForeignKey.CASCADE)])
data class ToolResultRow(val turnId:String,val invocationId:String,val ordinal:Int,val json:String)
@Entity(tableName="narration",foreignKeys=[ForeignKey(entity=TurnRow::class,parentColumns=["id"],childColumns=["turnId"],onDelete=ForeignKey.CASCADE)])
data class NarrationRow(@PrimaryKey val turnId:String,val text:String)
/** One current entity per row; no campaign aggregate or historical turns are embedded. */
@Entity(tableName="world_entity",primaryKeys=["campaignId","kind","entityId"],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class WorldEntityRow(val campaignId: String,val kind: String,val entityId: String,val json: String)
@Entity(tableName="knowledge_edge",primaryKeys=["campaignId","knowerId","factId"],indices=[Index(value=["campaignId","factId"])],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=FactRow::class,parentColumns=["campaignId","id"],childColumns=["campaignId","factId"])])
data class KnowledgeRow(val campaignId:String,val knowerId:String,val factId:String,val learnedAtSeq:Long,val method:String,val belief:String)
@Entity(tableName="npc_state",primaryKeys=["campaignId","id"],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class NpcRow(val campaignId:String,val id:String,val name:String,val locationId:String,val status:String,val personality:String,val nextActionAt:Long?)
@Entity(tableName="npc_relation",primaryKeys=["campaignId","npcId","dimension"],foreignKeys=[ForeignKey(entity=NpcRow::class,parentColumns=["campaignId","id"],childColumns=["campaignId","npcId"],onDelete=ForeignKey.CASCADE)])
data class NpcRelationRow(val campaignId:String,val npcId:String,val dimension:String,val value:Int)
@Entity(tableName="npc_goal",primaryKeys=["campaignId","npcId"],foreignKeys=[ForeignKey(entity=NpcRow::class,parentColumns=["campaignId","id"],childColumns=["campaignId","npcId"],onDelete=ForeignKey.CASCADE)])
data class NpcGoalRow(val campaignId:String,val npcId:String,val goal:String)
@Entity(tableName="fact",primaryKeys=["campaignId","id"],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class FactRow(val campaignId:String,val id:String,val text:String,val audience:String,val subjectId:String?,val locked:Boolean,val sourceJson:String)
@Serializable @Entity(tableName="claim",primaryKeys=["campaignId","id"],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class ClaimRow(val campaignId:String,val id:String,val actorId:String,val text:String,val sourceEventSeq:Long,val audience:String,val subjectId:String?,val supersedesId:String?)
@Entity(tableName="module_revision",primaryKeys=["id","version"])
data class ModuleRow(val id:String,val version:String,val hash:String,val title:String,val description:String,val state:String)
@Entity(tableName="module_source_part",primaryKeys=["moduleId","version","sourceId","part"],foreignKeys=[ForeignKey(entity=ModuleRow::class,parentColumns=["id","version"],childColumns=["moduleId","version"],onDelete=ForeignKey.CASCADE)])
data class SourcePartRow(val moduleId:String,val version:String,val sourceId:String,val part:Int,val text:String)
@Entity(tableName="rule_chunk", indices=[Index(value=["moduleId","version"]),Index("sourceId")])
data class RuleChunkRow(@PrimaryKey val id:String,val moduleId:String,val version:String,val moduleHash:String,val sourceId:String,val sourceHash:String,val anchor:String,val ruleId:String,val authority:String,val audience:String,val subjectId:String?,val quote:String,val offset:Int,val campaignId:String?=null)
@Fts4 @Entity(tableName="rule_fts")
data class RuleFtsRow(@PrimaryKey @ColumnInfo(name="rowid") val rowId:Int=0,val chunkId:String,val tokens:String)
@Entity(tableName="memory_note",indices=[Index(value=["campaignId","fromSeq"]),Index(value=["campaignId","status","audience"]),Index("sourceTurnId")],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=TurnRow::class,parentColumns=["id"],childColumns=["sourceTurnId"])])
data class MemoryRow(@PrimaryKey val id:String,val campaignId:String,val text:String,val entitiesJson:String,val fromSeq:Long,val toSeq:Long,val audience:String,val subjectId:String?,val status:String,val supersedesId:String?,val sourceTurnId:String?,val kind:String)
@Fts4 @Entity(tableName="memory_fts")
data class MemoryFtsRow(@PrimaryKey @ColumnInfo(name="rowid") val rowId:Int=0,val memoryId:String,val tokens:String)
@Serializable @Entity(tableName="summary",indices=[Index(value=["campaignId","fromSeq","toSeq"],unique=true)])
data class SummaryRow(@PrimaryKey val id:String,val campaignId:String,val fromSeq:Long,val toSeq:Long,val sourceRevision:Long,val text:String,val modelFingerprint:String,val valid:Boolean)
@Serializable @Entity(tableName="derived_job",indices=[Index(value=["campaignId","kind","fromSeq","toSeq"],unique=true)])
data class DerivedJobRow(@PrimaryKey val id:String,val campaignId:String,val kind:String,val fromSeq:Long,val toSeq:Long,val state:String,val error:String?)
@Serializable @Entity(tableName="reference_document")
data class DocumentRow(@PrimaryKey val id:String,val title:String,val hash:String,val audience:String,val subjectId:String?,val createdAt:Long)
@Serializable @Entity(tableName="document_part",primaryKeys=["documentId","part"],foreignKeys=[ForeignKey(entity=DocumentRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.CASCADE)])
data class DocumentPartRow(val documentId:String,val part:Int,val text:String)
@Entity(tableName="initial_state_part",primaryKeys=["campaignId","part"],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE)])
data class InitialStatePart(val campaignId:String,val part:Int,val text:String)
@Entity(tableName="campaign_document",primaryKeys=["campaignId","documentId"],indices=[Index("documentId")],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=DocumentRow::class,parentColumns=["id"],childColumns=["documentId"],onDelete=ForeignKey.CASCADE)])
data class CampaignDocumentRow(val campaignId:String,val documentId:String)
@Serializable @Entity(tableName="summary_source",primaryKeys=["summaryId","campaignId","eventSeq"],indices=[Index(value=["campaignId","eventSeq"])],foreignKeys=[ForeignKey(entity=SummaryRow::class,parentColumns=["id"],childColumns=["summaryId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=EventRow::class,parentColumns=["campaignId","seq"],childColumns=["campaignId","eventSeq"])])
data class SummarySourceRow(val summaryId:String,val campaignId:String,val eventSeq:Long)
@Serializable @Entity(tableName="correction_event",indices=[Index("campaignId"),Index("oldMemoryId"),Index("newMemoryId")],foreignKeys=[ForeignKey(entity=CampaignRow::class,parentColumns=["id"],childColumns=["campaignId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=MemoryRow::class,parentColumns=["id"],childColumns=["oldMemoryId"]),ForeignKey(entity=MemoryRow::class,parentColumns=["id"],childColumns=["newMemoryId"])])
data class CorrectionRow(@PrimaryKey val id:String,val campaignId:String,val oldMemoryId:String,val newMemoryId:String,val sourceRevision:Long)

@Dao interface AgentDao {
 @Query("SELECT * FROM campaign WHERE id=:id") fun campaign(id:String):CampaignRow?
 @Query("SELECT * FROM campaign ORDER BY updatedAt DESC,id LIMIT :limit OFFSET :offset") fun campaigns(offset:Int,limit:Int):List<CampaignRow>
 @Insert fun insertCampaign(row:CampaignRow)
 @Insert fun insertCampaignMeta(row:CampaignMetaRow)
 @Insert fun insertCampaignGroup(row:CampaignGroupRow)
 @Query("SELECT * FROM campaign ORDER BY updatedAt DESC,id") fun campaignRows():List<CampaignRow>
 @Query("SELECT * FROM campaign_group ORDER BY sortKey,id") fun campaignGroups():List<CampaignGroupRow>
 @Query("SELECT * FROM campaign_meta") fun campaignMetas():List<CampaignMetaRow>
 @Query("SELECT * FROM campaign_meta WHERE campaignId=:id") fun campaignMeta(id:String):CampaignMetaRow?
 @Query("SELECT COALESCE(MAX(sortKey),0) FROM campaign_group") fun maxGroupSortKey():Long
 @Query("UPDATE campaign_group SET name=:name WHERE id=:id") fun renameCampaignGroup(id:String,name:String)
 @Query("UPDATE campaign_group SET collapsed=:collapsed WHERE id=:id") fun setCampaignGroupCollapsed(id:String,collapsed:Boolean)
 @Query("UPDATE campaign_meta SET groupId=NULL WHERE groupId=:group") fun clearCampaignGroup(group:String)
 @Query("DELETE FROM campaign_group WHERE id=:id") fun deleteCampaignGroup(id:String)
 @Query("UPDATE campaign_meta SET groupId=:group WHERE campaignId=:id") fun moveCampaignToGroup(id:String,group:String?)
 @Query("UPDATE campaign_meta SET pinned=:pinned WHERE campaignId=:id") fun setCampaignPinned(id:String,pinned:Boolean)
 @Query("UPDATE campaign_meta SET hidden=:hidden WHERE campaignId=:id") fun setCampaignHidden(id:String,hidden:Boolean)
 @Query("UPDATE campaign_meta SET finished=:finished WHERE campaignId=:id") fun setCampaignFinished(id:String,finished:Boolean)
 @Query("UPDATE campaign_meta SET displayTitle=:title WHERE campaignId=:id") fun setCampaignDisplayTitle(id:String,title:String)
 @Query("UPDATE campaign_meta SET avatarGlyph=:glyph,avatarPair=:pair WHERE campaignId=:id") fun setCampaignAvatar(id:String,glyph:String?,pair:String?)
 @Query("UPDATE campaign_meta SET groupId=:group,pinned=:pinned,hidden=:hidden,finished=:finished,displayTitle=:title,avatarGlyph=:glyph,avatarPair=:pair WHERE campaignId=:id") fun updateCampaignShelfMeta(id:String,group:String?,pinned:Boolean,hidden:Boolean,finished:Boolean,title:String?,glyph:String?,pair:String?)
 @Query("DELETE FROM campaign WHERE id=:id") fun deleteCampaign(id:String)
 @Insert fun insertInitial(rows:List<InitialStatePart>)
 @Query("SELECT * FROM initial_state_part WHERE campaignId=:id ORDER BY part") fun initial(id:String):List<InitialStatePart>
 @Update fun updateCampaign(row:CampaignRow)
 @Query("SELECT * FROM turn WHERE campaignId=:campaignId AND id=:id") fun turn(campaignId:String,id:String):TurnRow?
 @Query("SELECT * FROM turn WHERE campaignId=:campaignId AND clientMessageId=:clientId") fun clientTurn(campaignId:String,clientId:String):TurnRow?
 @Query("SELECT * FROM turn WHERE campaignId=:campaignId AND (:before IS NULL OR ordinal<:before) ORDER BY ordinal DESC LIMIT :limit") fun turns(campaignId:String,before:Long?,limit:Int):List<TurnRow>
 @Insert fun insertTurn(row:TurnRow)
 @Update fun updateTurn(row:TurnRow)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertAttempts(rows:List<AttemptRow>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertTools(rows:List<ToolResultRow>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertNarration(row:NarrationRow)
 @Query("SELECT * FROM model_attempt WHERE turnId=:id ORDER BY ordinal") fun attempts(id:String):List<AttemptRow>
 @Query("SELECT * FROM tool_result WHERE turnId=:id ORDER BY ordinal") fun tools(id:String):List<ToolResultRow>
 @Query("SELECT * FROM narration WHERE turnId=:id") fun narration(id:String):NarrationRow?
 @Query("SELECT * FROM event WHERE campaignId=:campaignId AND seq>:after ORDER BY seq LIMIT :limit") fun events(campaignId:String,after:Long,limit:Int):List<EventRow>
 @Insert fun insertEvent(row:EventRow)
 @Query("SELECT * FROM world_entity WHERE campaignId=:campaignId") fun world(campaignId:String):List<WorldEntityRow>
 @Query("DELETE FROM world_entity WHERE campaignId=:campaignId") fun clearWorld(campaignId:String)
 @Insert fun insertWorld(rows:List<WorldEntityRow>)
 @Query("SELECT * FROM knowledge_edge WHERE campaignId=:campaignId") fun knowledge(campaignId:String):List<KnowledgeRow>
 @Query("DELETE FROM knowledge_edge WHERE campaignId=:campaignId") fun clearKnowledge(campaignId:String)
 @Insert fun insertKnowledge(rows:List<KnowledgeRow>)
 @Query("SELECT * FROM npc_state WHERE campaignId=:id") fun npcs(id:String):List<NpcRow>
 @Query("SELECT * FROM npc_relation WHERE campaignId=:id") fun relations(id:String):List<NpcRelationRow>
 @Query("SELECT * FROM npc_goal WHERE campaignId=:id") fun goals(id:String):List<NpcGoalRow>
 @Query("SELECT * FROM fact WHERE campaignId=:id") fun facts(id:String):List<FactRow>
 @Query("DELETE FROM npc_state WHERE campaignId=:id") fun clearNpcs(id:String)
 @Query("DELETE FROM fact WHERE campaignId=:id") fun clearFacts(id:String)
 @Insert fun insertNpcs(rows:List<NpcRow>)
 @Insert fun insertRelations(rows:List<NpcRelationRow>)
 @Insert fun insertGoals(rows:List<NpcGoalRow>)
 @Insert fun insertFacts(rows:List<FactRow>)
 @Insert fun insertClaim(row:ClaimRow)
 @Query("SELECT * FROM claim WHERE campaignId=:campaign ORDER BY id LIMIT :limit OFFSET :offset") fun claims(campaign:String,offset:Int,limit:Int):List<ClaimRow>
 @Query("SELECT * FROM module_revision WHERE id=:id AND version=:version") fun module(id:String,version:String):ModuleRow?
 @Query("SELECT * FROM module_revision WHERE state='READY' ORDER BY title,id,version") fun modules():List<ModuleRow>
 @Insert fun insertModule(row:ModuleRow)
 @Query("SELECT * FROM module_source_part WHERE moduleId=:id AND version=:version AND sourceId=:source ORDER BY part") fun parts(id:String,version:String,source:String):List<SourcePartRow>
 @Insert fun insertParts(rows:List<SourcePartRow>)
 @Insert fun insertRule(row:RuleChunkRow)
 @Query("INSERT INTO rule_fts(chunkId,tokens) VALUES(:id,:tokens)") fun insertRuleIndex(id:String,tokens:String)
 fun insertRuleFts(row:RuleFtsRow) {insertRuleIndex(row.chunkId,row.tokens)}
 @Query("SELECT r.* FROM rule_chunk r WHERE moduleId=:moduleId AND version=:version AND moduleHash=:hash AND (campaignId IS NULL OR campaignId=:campaign) AND (:gm OR audience='PUBLIC' OR (audience=:audience AND subjectId=:subject)) AND (ruleId=:query OR id IN (SELECT chunkId FROM rule_fts WHERE rule_fts MATCH :tokens)) ORDER BY CASE WHEN ruleId=:query THEN 0 WHEN instr(quote,:query)>0 THEN 1 ELSE 2 END,offset,id LIMIT :limit")
 fun searchRules(moduleId:String,version:String,hash:String,campaign:String,gm:Boolean,audience:String,subject:String?,query:String,tokens:String,limit:Int):List<RuleChunkRow>
 @Query("SELECT * FROM rule_chunk WHERE moduleId=:id AND version=:version AND moduleHash=:hash AND (campaignId IS NULL OR campaignId=:campaign) AND sourceId=:source AND anchor=:anchor AND (:gm OR audience='PUBLIC' OR (audience=:audience AND subjectId=:subject)) LIMIT 1") fun readRule(id:String,version:String,hash:String,campaign:String,source:String,anchor:String,gm:Boolean,audience:String,subject:String?):RuleChunkRow?
 @Query("SELECT * FROM memory_note WHERE campaignId=:campaignId AND id=:id") fun memory(campaignId:String,id:String):MemoryRow?
 @Insert fun insertMemory(row:MemoryRow)
 @Update fun updateMemory(row:MemoryRow)
 @Query("INSERT INTO memory_fts(memoryId,tokens) VALUES(:id,:tokens)") fun insertMemoryIndex(id:String,tokens:String)
 fun insertMemoryFts(row:MemoryFtsRow) {insertMemoryIndex(row.memoryId,row.tokens)}
 @Query("DELETE FROM memory_fts WHERE memoryId=:id") fun deleteMemoryFts(id:String)
 @Query("SELECT m.* FROM memory_note m WHERE campaignId=:campaignId AND status='ACTIVE' AND (:gm OR audience='PUBLIC' OR (audience=:audience AND subjectId=:subject)) AND id IN (SELECT memoryId FROM memory_fts WHERE memory_fts MATCH :tokens) ORDER BY fromSeq DESC,id LIMIT :limit") fun searchMemory(campaignId:String,gm:Boolean,audience:String,subject:String?,tokens:String,limit:Int):List<MemoryRow>
 @Query("SELECT * FROM memory_note WHERE campaignId=:campaignId AND status='ACTIVE' AND (:before IS NULL OR fromSeq<:before) AND (:gm OR audience='PUBLIC' OR (audience=:audience AND subjectId=:subject)) ORDER BY fromSeq DESC,id LIMIT :limit") fun recentMemory(campaignId:String,gm:Boolean,audience:String,subject:String?,before:Long?,limit:Int):List<MemoryRow>
 @Query("SELECT * FROM memory_note WHERE campaignId=:campaignId AND id>:after ORDER BY id LIMIT :limit") fun exportMemory(campaignId:String,after:String,limit:Int):List<MemoryRow>
 @Insert fun insertSummary(row:SummaryRow)
 @Insert fun insertSummarySources(rows:List<SummarySourceRow>)
 @Insert fun insertCorrection(row:CorrectionRow)
 @Query("SELECT * FROM correction_event WHERE campaignId=:campaign ORDER BY id LIMIT :limit OFFSET :offset") fun corrections(campaign:String,offset:Int,limit:Int):List<CorrectionRow>
 @Query("SELECT * FROM summary_source WHERE campaignId=:campaign ORDER BY summaryId,eventSeq LIMIT :limit OFFSET :offset") fun summarySources(campaign:String,offset:Int,limit:Int):List<SummarySourceRow>
 @Query("SELECT * FROM derived_job WHERE id=:id") fun job(id:String):DerivedJobRow?
 @Query("UPDATE summary SET valid=0 WHERE campaignId=:campaignId AND fromSeq<=:toSeq AND toSeq>=:fromSeq") fun invalidateSummaries(campaignId:String,fromSeq:Long,toSeq:Long)
 @Query("UPDATE derived_job SET state='INTERRUPTED',error='SOURCE_CORRECTED' WHERE campaignId=:campaignId AND fromSeq<=:toSeq AND toSeq>=:fromSeq AND state='RUNNING'") fun interruptCorrectedJobs(campaignId:String,fromSeq:Long,toSeq:Long)
 @Query("SELECT * FROM summary WHERE campaignId=:campaignId ORDER BY fromSeq LIMIT :limit OFFSET :offset") fun summaries(campaignId:String,offset:Int,limit:Int):List<SummaryRow>
 @Insert(onConflict=OnConflictStrategy.IGNORE) fun insertJob(row:DerivedJobRow)
 @Query("SELECT * FROM derived_job WHERE campaignId=:campaignId ORDER BY fromSeq LIMIT :limit OFFSET :offset") fun jobs(campaignId:String,offset:Int,limit:Int):List<DerivedJobRow>
 @Update fun updateJob(row:DerivedJobRow)
 @Insert fun insertDocument(row:DocumentRow)
 @Insert fun insertDocumentParts(rows:List<DocumentPartRow>)
 @Query("SELECT * FROM reference_document ORDER BY createdAt DESC LIMIT :limit OFFSET :offset") fun documents(offset:Int,limit:Int):List<DocumentRow>
 @Query("SELECT * FROM document_part WHERE documentId=:id ORDER BY part") fun documentParts(id:String):List<DocumentPartRow>
 @Query("SELECT * FROM reference_document WHERE id=:id") fun document(id:String):DocumentRow?
 @Insert fun attachDocument(row:CampaignDocumentRow)
 @Query("SELECT d.* FROM reference_document d INNER JOIN campaign_document c ON d.id=c.documentId WHERE c.campaignId=:campaign ORDER BY d.id LIMIT :limit OFFSET :offset") fun attachedDocuments(campaign:String,offset:Int,limit:Int):List<DocumentRow>
}

@Database(entities=[CampaignRow::class,CampaignGroupRow::class,CampaignMetaRow::class,TurnRow::class,EventRow::class,AttemptRow::class,ToolResultRow::class,NarrationRow::class,WorldEntityRow::class,KnowledgeRow::class,NpcRow::class,NpcRelationRow::class,NpcGoalRow::class,FactRow::class,ClaimRow::class,ModuleRow::class,SourcePartRow::class,RuleChunkRow::class,RuleFtsRow::class,MemoryRow::class,MemoryFtsRow::class,SummaryRow::class,DerivedJobRow::class,DocumentRow::class,DocumentPartRow::class,InitialStatePart::class,CampaignDocumentRow::class,SummarySourceRow::class,CorrectionRow::class],version=1,exportSchema=true)
abstract class AgentDatabase:RoomDatabase() {
 abstract fun dao():AgentDao
 companion object {
  @Volatile private var instance:AgentDatabase?=null
  fun get(context:Context):AgentDatabase=instance?:synchronized(this){ instance?:Room.databaseBuilder(context.applicationContext,AgentDatabase::class.java,"d20-v05.db").build().also{instance=it} }
 }
}
