package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.*
import java.security.MessageDigest
import java.util.UUID
import com.ai.assistance.operit.rpg.agent.rules.GenericD100
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import kotlinx.serialization.encodeToString

/** FTS receives only quoted generated terms, never user-provided MATCH syntax. */
object ChineseSearch {
 fun terms(text:String):List<String> {
  val out=linkedSetOf<String>()
  Regex("[\\p{IsHan}]+|[a-zA-Z0-9_:-]+").findAll(text.lowercase()).forEach { match ->
   val s=match.value
   if(s.first().code in 0x3400..0x9fff) { s.forEach{out+=it.toString()}; s.windowed(2).forEach{out+=it} }
   else out+=s
  }
  return out.take(256)
 }
 fun index(text:String)=terms(text).joinToString(" ")
 fun query(text:String):String {
  val all=terms(text);val phrases=all.filter{it.length>1}
  return (if(phrases.isEmpty())all else phrases).take(64).joinToString(" OR "){"\"${it.replace("\"","")}\""}.ifEmpty { "\"__empty_query__\"" }
 }
}
internal fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
/** Keep both UTF-8 storage parts and searchable quotes below Android cursor limits. */
internal fun textParts(text:String,maxBytes:Int=60*1024):List<String> {
 val parts=mutableListOf<String>(); val current=StringBuilder(); var bytes=0; var offset=0
 while(offset<text.length) {
  val cp=text.codePointAt(offset); val unit=String(Character.toChars(cp)); val size=unit.toByteArray(Charsets.UTF_8).size
  if(bytes+size>maxBytes) { parts+=current.toString(); current.setLength(0); bytes=0 }
  current.append(unit); bytes+=size; offset+=Character.charCount(cp)
 }
 if(current.isNotEmpty() || parts.isEmpty()) parts+=current.toString()
 return parts
}
internal fun RoleContext.canSee(v:Visibility)=audience==Audience.GM || v.audience==Audience.PUBLIC || (audience==v.audience && subjectId!=null && subjectId==v.subjectId)

class RoomModuleCatalog(private val db:AgentDatabase):ModuleCatalog {
 override fun get(ref:VersionRef):ValidatedModule {
  val row=db.dao().module(ref.id,ref.version)?:error("MODULE_NOT_INSTALLED")
  require(row.hash==ref.hash && row.state=="READY") { "MODULE_VERSION_MISMATCH" }
  return decode(db.dao().parts(ref.id,ref.version,"@validated").joinToString(""){it.text})
 }
 override fun list():List<ValidatedModule> =db.dao().modules().map{get(VersionRef(it.id,it.version,it.hash))}
 fun headers():List<ModuleRow> =db.dao().modules()
 override fun install(module:ValidatedModule)=db.atomic {
  validateStoredModule(module)
  val m=module.manifest; val existing=db.dao().module(m.moduleId,m.version)
  if(existing!=null) { require(existing.hash==module.contentHash){"MODULE_VERSION_CONFLICT"}; return@atomic }
  require(m.formatVersion==1 && m.engineApiVersion==AGENT_API_VERSION)
  db.dao().insertModule(ModuleRow(m.moduleId,m.version,module.contentHash,m.title,m.description,"READY"))
  fun write(source:String,text:String) { db.dao().insertParts(textParts(text).mapIndexed { i,p->SourcePartRow(m.moduleId,m.version,source,i,p) }) }
  write("@validated",encode(module))
  module.sources.forEach{(id,text)->write(id,text)}
  module.rules.evidence.forEachIndexed { i,e->
   require(e.quote.length<=1200) { "RULE_QUOTE_TOO_LARGE" }
   val id="${m.moduleId}:${m.version}:rule:$i"
   db.dao().insertRule(RuleChunkRow(id,m.moduleId,m.version,module.contentHash,e.source.sourceId,e.source.sourceHash,e.source.anchor,e.ruleId,e.source.authority,e.visibility.audience.name,e.visibility.subjectId,e.quote,0))
   db.dao().insertRuleFts(RuleFtsRow(chunkId=id,tokens=ChineseSearch.index("${e.ruleId} ${e.source.anchor} ${e.quote}")))
  }
  // Index raw paragraphs too; they remain evidence only and never become executable effects.
  module.sources.forEach { (source,text)->
   text.chunked(700).forEachIndexed { i,quote->
    val evidence=module.rules.evidence.filter{it.source.sourceId==source}
    // A source without an explicit public scope is GM-only, avoiding accidental secret publication.
    val scope=if(evidence.isNotEmpty() && evidence.all{it.visibility.audience==Audience.PUBLIC}) Visibility(Audience.PUBLIC) else Visibility(Audience.GM)
    val id="${m.moduleId}:${m.version}:source:$source:$i"
    db.dao().insertRule(RuleChunkRow(id,m.moduleId,m.version,module.contentHash,source,sha256(text),"offset:${i*700}","", "source",scope.audience.name,scope.subjectId,quote,i*700))
    db.dao().insertRuleFts(RuleFtsRow(chunkId=id,tokens=ChineseSearch.index(quote)))
   }
  }
 }
}

internal fun validateStoredModule(module:ValidatedModule) {
 val manifest=module.manifest.copy(files=module.manifest.files.sortedBy{it.path})
 require(module.contentHash==sha256(StrictJson.json.encodeToString(manifest))) { "MODULE_CONTENT_HASH_MISMATCH" }
 require(manifest.files.map{it.path}.toSet()==module.originalFiles.keys && manifest.files.size==module.originalFiles.size){"MODULE_FILES_MISSING"}
 manifest.files.forEach{f->val text=module.originalFiles.getValue(f.path);require(text.toByteArray(Charsets.UTF_8).size.toLong()==f.size && sha256(text)==f.sha256){"MODULE_SOURCE_HASH_MISMATCH"}}
 require(decode<ModuleWorld>(module.originalFiles.getValue("module/world.json"))==module.world && decode<ModuleRules>(module.originalFiles.getValue("module/rules.json"))==module.rules){"MODULE_DEFINITION_MISMATCH"}
 require(module.originalFiles.filterKeys{it.startsWith("module/sources/") }==module.sources)
 module.rules.evidence.forEach{e->val source=module.sources.getValue(e.source.sourceId);require(sha256(source)==e.source.sourceHash && source.contains(e.quote) && e.quote.isNotBlank())}
 GenericD100().validateModule(module)
}

class RoomRuleRepository(private val db:AgentDatabase):RuleRepository {
 override fun search(role:RoleContext,module:VersionRef,query:String,limit:Int):List<RuleEvidence> {
  bind(role,module)
  val terms=ChineseSearch.terms(query).filter{it.length>1}
  return db.dao().searchRules(module.id,module.version,module.hash,role.campaignId,role.audience==Audience.GM,role.audience.name,role.subjectId,query,ChineseSearch.query(query),200)
   .sortedWith(compareByDescending<RuleChunkRow>{if(it.ruleId==query)100000 else terms.sumOf{term->if((it.ruleId+it.quote).contains(term))term.length else 0}}.thenBy{it.offset}.thenBy{it.id})
   .take(page(limit).coerceAtMost(5)).map{it.evidence()}
 }
 override fun read(role:RoleContext,module:VersionRef,sourceId:String,anchor:String):RuleEvidence? {
  bind(role,module)
  return db.dao().readRule(module.id,module.version,module.hash,role.campaignId,sourceId,anchor,role.audience==Audience.GM,role.audience.name,role.subjectId)?.evidence()
 }
 private fun bind(role:RoleContext,module:VersionRef) {
  val c=db.dao().campaign(role.campaignId)?:error("CAMPAIGN_NOT_FOUND")
  require(c.moduleId==module.id && c.moduleVersion==module.version && c.moduleHash==module.hash){"MODULE_VERSION_MISMATCH"}
 }
 private fun RuleChunkRow.evidence()=RuleEvidence(ruleId,SourceRef(sourceId,sourceHash,anchor,authority),quote,Visibility(Audience.valueOf(audience),subjectId))
}

class RoomMemoryRepository(private val db:AgentDatabase):MemoryRepository {
 override fun search(role:RoleContext,query:String,entityIds:List<String>,limit:Int):List<MemoryItem> {
  require(db.dao().campaign(role.campaignId)!=null)
  // Entity terms participate in the bounded SQL query; visibility is filtered before materialization.
  return db.dao().searchMemory(role.campaignId,role.audience==Audience.GM,role.audience.name,role.subjectId,ChineseSearch.query((listOf(query)+entityIds).joinToString(" ")),page(limit).coerceAtMost(5)).map{it.item()}
 }
 override fun recent(role:RoleContext,limit:Int):List<MemoryItem> =list(role,null,limit)
 fun list(role:RoleContext,beforeSeq:Long?,limit:Int):List<MemoryItem> =db.dao().recentMemory(role.campaignId,role.audience==Audience.GM,role.audience.name,role.subjectId,beforeSeq,page(limit)).map{it.item()}
 override fun save(item:MemoryItem)=db.atomic {
  require(item.text.isNotBlank() && item.text.length<=12000 && item.fromSeq>=0 && item.toSeq>=item.fromSeq)
  val c=db.dao().campaign(item.campaignId)?:error("CAMPAIGN_NOT_FOUND")
  require(item.toSeq<=c.lastEventSeq) { "INVALID_MEMORY_SOURCE" }
  require(item.status in setOf("ACTIVE","SUPERSEDED","INVALID"))
  require(com.ai.assistance.operit.rpg.agent.rules.validVisibility(item.visibility))
  require(db.dao().memory(item.campaignId,item.id)==null) { "MEMORY_IMMUTABLE" }
  db.dao().insertMemory(item.row())
  if(item.status=="ACTIVE") db.dao().insertMemoryFts(MemoryFtsRow(memoryId=item.id,tokens=ChineseSearch.index(item.text+" "+item.entityIds.joinToString(" "))))
 }
 fun correct(role:RoleContext,oldId:String,newText:String):MemoryItem=db.atomic {
  val old=db.dao().memory(role.campaignId,oldId)?:error("MEMORY_NOT_FOUND")
  require(role.canSee(Visibility(Audience.valueOf(old.audience),old.subjectId)) && old.status=="ACTIVE") { "MEMORY_NOT_FOUND" }
  require(newText.isNotBlank() && newText.length<=12000)
  val replacement=old.item().copy(id=UUID.randomUUID().toString(),text=newText,supersedesId=old.id)
  db.dao().updateMemory(old.copy(status="SUPERSEDED")); db.dao().deleteMemoryFts(old.id)
  save(replacement); db.dao().invalidateSummaries(old.campaignId,old.fromSeq,old.toSeq)
  db.dao().insertCorrection(CorrectionRow(UUID.randomUUID().toString(),old.campaignId,old.id,replacement.id,db.dao().campaign(old.campaignId)!!.revision))
  replacement
 }
 internal fun MemoryRow.item()=MemoryItem(id,campaignId,text,decode(entitiesJson),fromSeq,toSeq,Visibility(Audience.valueOf(audience),subjectId),status,supersedesId)
 private fun MemoryItem.row()=MemoryRow(id,campaignId,text,encode(entityIds),fromSeq,toSeq,visibility.audience.name,visibility.subjectId,status,supersedesId)
}

/** Local persistence for explicitly enabled paid summary tasks. This service never calls a model. */
class DerivedTaskService(private val db:AgentDatabase) {
 fun list(campaignId:String,offset:Int=0,limit:Int=100)=db.dao().jobs(campaignId,offset,page(limit))
 fun start(id:String):DerivedJobRow=db.atomic {
  val job=db.dao().job(id)?:error("JOB_NOT_FOUND")
  require(job.state in setOf("PENDING","FAILED","INTERRUPTED")){"JOB_NOT_RETRYABLE"}
  job.copy(state="RUNNING",error=null).also{db.dao().updateJob(it)}
 }
 fun fail(id:String,error:String)=db.atomic {val j=db.dao().job(id)?:error("JOB_NOT_FOUND");require(j.state=="RUNNING");db.dao().updateJob(j.copy(state="FAILED",error=error.take(1000)))}
 fun complete(id:String,text:String,modelFingerprint:String)=db.atomic {
  val j=db.dao().job(id)?:error("JOB_NOT_FOUND");require(j.state=="RUNNING" && j.kind=="SUMMARY")
  require(text.isNotBlank() && text.length<=12000 && modelFingerprint.isNotBlank())
  val c=db.dao().campaign(j.campaignId)?:error("CAMPAIGN_NOT_FOUND")
  require(j.fromSeq>=1 && j.toSeq<=c.lastEventSeq && j.fromSeq<=j.toSeq)
  val summary=SummaryRow(UUID.randomUUID().toString(),j.campaignId,j.fromSeq,j.toSeq,c.revision,text,modelFingerprint,true)
  db.dao().insertSummary(summary)
  var seq=j.fromSeq-1
  while(seq<j.toSeq) {val rows=db.dao().events(j.campaignId,seq,200).takeWhile{it.seq<=j.toSeq};require(rows.isNotEmpty());db.dao().insertSummarySources(rows.map{SummarySourceRow(summary.id,j.campaignId,it.seq)});seq=rows.last().seq}
  db.dao().updateJob(j.copy(state="COMPLETE",error=null))
 }
 fun interrupt(campaignId:String)=db.atomic {var offset=0;while(true){val rows=db.dao().jobs(campaignId,offset,200);if(rows.isEmpty())break;rows.filter{it.state=="RUNNING"}.forEach{db.dao().updateJob(it.copy(state="INTERRUPTED",error="INTERRUPTED"))};offset+=rows.size}}
}

data class ReferenceDocument(val id:String,val title:String,val hash:String,val createdAt:Long)
class ReferenceDocumentService(private val db:AgentDatabase) {
 fun importText(title:String,text:String,visibility:Visibility):String=db.atomic {
  require(title.isNotBlank() && title.length<=200 && text.toByteArray(Charsets.UTF_8).size<=5*1024*1024)
  val id=UUID.randomUUID().toString()
  db.dao().insertDocument(DocumentRow(id,title,sha256(text),visibility.audience.name,visibility.subjectId,System.currentTimeMillis()))
  db.dao().insertDocumentParts(textParts(text).mapIndexed{i,p->DocumentPartRow(id,i,p)})
  id
 }
 fun list(offset:Int=0,limit:Int=100):List<ReferenceDocument> { require(offset>=0); return db.dao().documents(offset,page(limit)).map{ReferenceDocument(it.id,it.title,it.hash,it.createdAt)} }
 fun read(id:String):String=db.dao().documentParts(id).joinToString(""){it.text}
 fun attach(campaignId:String,documentId:String)=db.atomic {
  val c=db.dao().campaign(campaignId)?:error("CAMPAIGN_NOT_FOUND")
  val d=db.dao().document(documentId)?:error("DOCUMENT_NOT_FOUND")
  db.dao().attachDocument(CampaignDocumentRow(campaignId,documentId))
  var offset=0
  db.dao().documentParts(documentId).forEach { part->part.text.chunked(700).forEach { quote->
   val id="$campaignId:reference:$documentId:$offset"
   db.dao().insertRule(RuleChunkRow(id,c.moduleId,c.moduleVersion,c.moduleHash,documentId,d.hash,"offset:$offset","","reference",d.audience,d.subjectId,quote,offset,campaignId))
   db.dao().insertRuleFts(RuleFtsRow(chunkId=id,tokens=ChineseSearch.index("${d.title} $quote")));offset+=quote.length
  }}
 }
}
