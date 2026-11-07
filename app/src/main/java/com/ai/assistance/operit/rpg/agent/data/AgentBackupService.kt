package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.RuleAdapters
import com.ai.assistance.operit.rpg.agent.rules.initialWorld
import java.io.*
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Snapshot creation holds the DB write lock; ZIP/network IO happens after releasing it.
 * History is copied in 200-row pages and parsed one bounded JSONL record at a time.
 */
class AgentBackupService(private val db:AgentDatabase,private val tempDirectory:File) {
 constructor(db:AgentDatabase):this(db,File(System.getProperty("java.io.tmpdir")))
 private val names=setOf("campaign.json","initial.json","module.json","turns.jsonl","events.jsonl","memory.jsonl","summaries.jsonl","jobs.jsonl","documents.jsonl","document-parts.jsonl","corrections.jsonl","summary-sources.jsonl","claims.jsonl")
 fun export(campaignId:String,output:OutputStream) {
  val dir=createStage()
  try {
   val manifest=db.atomic {
    val store=RoomAgentStore(db); val c=store.loadCampaign(campaignId)?:error("CAMPAIGN_NOT_FOUND")
    require(db.dao().campaign(campaignId)?.pendingTurnId==null){"FINISH_OR_CANCEL_PENDING_TURN"}
    val safe=c.copy(modelFingerprint="")
    write(dir,"campaign.json",encode(safe))
    write(dir,"initial.json",db.dao().initial(campaignId).joinToString(""){it.text})
    write(dir,"module.json",encode(RoomModuleCatalog(db).get(c.module)))
    lines(dir,"turns.jsonl") { emit->
     var before:Long?=null
     while(true) { val rows=store.history(campaignId,before,200); if(rows.isEmpty()) break
      rows.forEach { t-> require(t.phase in setOf(TurnPhase.COMPLETE,TurnPhase.CANCELLED)); emit(encode(t.copy(attempts=emptyList(),tools=emptyList()))) }
      before=rows.last().ordinal
     }
    }
    lines(dir,"events.jsonl") { emit->var after=0L; while(true) { val rows=store.events(campaignId,after,200); if(rows.isEmpty())break; rows.forEach{emit(encode(it))}; after=rows.last().seq } }
    lines(dir,"memory.jsonl") { emit->var after=""; while(true) { val rows=db.dao().exportMemory(campaignId,after,200); if(rows.isEmpty())break; rows.forEach{r->emit(encode(MemoryItem(r.id,r.campaignId,r.text,decode(r.entitiesJson),r.fromSeq,r.toSeq,Visibility(Audience.valueOf(r.audience),r.subjectId),r.status,r.supersedesId,r.sourceTurnId,r.kind)))}; after=rows.last().id } }
    lines(dir,"summaries.jsonl"){emit->var offset=0; while(true){val rows=db.dao().summaries(campaignId,offset,200);if(rows.isEmpty())break;rows.forEach{emit(encode(it.copy(modelFingerprint="")))};offset+=rows.size}}
    lines(dir,"jobs.jsonl"){emit->var offset=0; while(true){val rows=db.dao().jobs(campaignId,offset,200);if(rows.isEmpty())break;rows.forEach{emit(encode(it))};offset+=rows.size}}
    lines(dir,"corrections.jsonl"){emit->var offset=0;while(true){val rows=db.dao().corrections(campaignId,offset,200);if(rows.isEmpty())break;rows.forEach{emit(encode(it))};offset+=rows.size}}
    lines(dir,"summary-sources.jsonl"){emit->var offset=0;while(true){val rows=db.dao().summarySources(campaignId,offset,200);if(rows.isEmpty())break;rows.forEach{emit(encode(it))};offset+=rows.size}}
    lines(dir,"claims.jsonl"){emit->var offset=0;while(true){val rows=db.dao().claims(campaignId,offset,200);if(rows.isEmpty())break;rows.forEach{emit(encode(it))};offset+=rows.size}}
    File(dir,"document-parts.jsonl").bufferedWriter(Charsets.UTF_8).use { partsWriter->lines(dir,"documents.jsonl"){emit->var offset=0;while(true){val docs=db.dao().attachedDocuments(campaignId,offset,100);if(docs.isEmpty())break;docs.forEach{doc->emit(encode(doc));db.dao().documentParts(doc.id).forEach{partsWriter.write(encode(it));partsWriter.newLine()}};offset+=docs.size}} }
    BackupManifestV2(2,LONGFORM_ENGINE,1,1,c.module,c.rules,names.sorted().map{n->val f=File(dir,n);FileDigest(n,f.length(),digest(f))})
   }
   ZipOutputStream(BufferedOutputStream(output)).use { zip->
    zip.putNextEntry(ZipEntry("manifest.json"));zip.write(encode(manifest).toByteArray(Charsets.UTF_8));zip.closeEntry()
    names.sorted().forEach{n->zip.putNextEntry(ZipEntry(n));File(dir,n).inputStream().use{it.copyTo(zip,32*1024)};zip.closeEntry()}
   }
  } finally { dir.deleteRecursively() }
 }
 fun preview(input:InputStream):CampaignHeader {
  val dir=createStage()
  try {
   unzip(input,dir)
   val manifest=decode<BackupManifestV2>(read(dir,"manifest.json"))
   require(manifest.archiveFormatVersion==2 && manifest.engineId==LONGFORM_ENGINE && manifest.engineVersion==1 && manifest.eventSchemaVersion==1){"UNSUPPORTED_BACKUP_VERSION"}
   require(manifest.files.map{it.path}.toSet()==names && manifest.files.size==names.size)
   manifest.files.forEach{f->val file=File(dir,f.path);require(file.length()==f.size && digest(file)==f.sha256){"BACKUP_HASH_MISMATCH"}}
   val c=decode<Campaign>(read(dir,"campaign.json"))
   require(c.module==manifest.module && c.rules==manifest.rules)
   return CampaignHeader(CampaignRef(LONGFORM_ENGINE,c.id),c.title,c.updatedAt)
  } finally {dir.deleteRecursively()}
 }
 fun importBackup(input:InputStream):CampaignRef {
  val dir=createStage()
  try {
   unzip(input,dir)
   val manifest=decode<BackupManifestV2>(read(dir,"manifest.json"))
   require(manifest.archiveFormatVersion==2 && manifest.engineId==LONGFORM_ENGINE && manifest.engineVersion==1 && manifest.eventSchemaVersion==1){"UNSUPPORTED_BACKUP_VERSION"}
   require(manifest.files.map{it.path}.toSet()==names && manifest.files.size==names.size){"INVALID_FILE_MANIFEST"}
   manifest.files.forEach { f->val file=File(dir,f.path);require(file.length()==f.size && digest(file)==f.sha256){"BACKUP_HASH_MISMATCH:${f.path}"} }
   val module=decode<ValidatedModule>(read(dir,"module.json"));val adapter=RuleAdapters.forModule(module);validateStoredModule(module)
   require(VersionRef(module.manifest.moduleId,module.manifest.version,module.contentHash)==manifest.module)
   val original=decode<Campaign>(read(dir,"campaign.json"));val initial=decode<WorldState>(read(dir,"initial.json"))
   require(original.module==manifest.module && original.rules==manifest.rules && original.modelFingerprint.isEmpty())
   adapter.validateState(initial,module)
   val canonicalInitial=canonicalWorld(initial)
   require(module.world.characters.any{canonicalWorld(initialWorld(module,it.id))==canonicalInitial}){"INVALID_INITIAL_STATE"}
   var replay=initial;var seq=0L
   eachLine(dir,"events.jsonl"){line->val event=decode<WorldEvent>(line);require(event.campaignId==original.id && event.seq==++seq && event.eventVersion==1);replay=adapter.applyRecordedEvent(replay,event,module)}
   require(seq==original.lastEventSeq && canonicalWorld(replay)==canonicalWorld(original.world) && original.revision==seq){"BACKUP_REPLAY_MISMATCH"}
   val newId=UUID.randomUUID().toString()
   fun mapped(id:String)="$newId:$id"
   return db.atomic {
    RoomModuleCatalog(db).install(module)
    val store=RoomAgentStore(db)
    val restored=original.copy(id=newId,modelFingerprint="",updatedAt=System.currentTimeMillis())
    store.createCampaign(restored)
    // Preserve the immutable starting state rather than replacing it with the final projection.
    db.openHelper.writableDatabase.execSQL("DELETE FROM initial_state_part WHERE campaignId=?",arrayOf(newId))
    db.dao().insertInitial(textParts(encode(initial)).mapIndexed{i,p->InitialStatePart(newId,i,p)})
    var restoredTurnCount=0L
    eachLine(dir,"turns.jsonl"){line->val t=decode<Turn>(line)
     require(t.campaignId==original.id && t.phase in setOf(TurnPhase.COMPLETE,TurnPhase.CANCELLED) && t.attempts.isEmpty() && t.tools.isEmpty())
     require(t.ordinal in 1 until original.nextTurnOrdinal && t.baseRevision in 0..original.revision)
     val nt=t.copy(id=mapped(t.id),campaignId=newId,clientMessageId=mapped(t.clientMessageId),result=t.result?.let{it.copy(turnId=mapped(it.turnId))})
     store.persistTurn(nt)
     restoredTurnCount++
    }
    require(restoredTurnCount==original.nextTurnOrdinal-1){"INCOMPLETE_TURN_HISTORY"}
    eachLine(dir,"events.jsonl"){line->val e=decode<WorldEvent>(line);val t=db.dao().turn(newId,mapped(e.turnId))?:error("DANGLING_EVENT_TURN");val turn=decode<Turn>(t.json);val result=requireNotNull(turn.result);require(result.eventSeq==e.seq && result.dice==e.dice && result.turnId==turn.id){"EVENT_RESULT_MISMATCH"};val ne=e.copy(campaignId=newId,turnId=mapped(e.turnId));db.dao().insertEvent(EventRow(newId,ne.seq,ne.turnId,ne.eventVersion,encode(ne)))}
    val memory=RoomMemoryRepository(db)
    eachLine(dir,"memory.jsonl"){line->val m=decode<MemoryItem>(line);require(m.campaignId==original.id);memory.save(m.copy(id=mapped(m.id),campaignId=newId,supersedesId=m.supersedesId?.let(::mapped),sourceTurnId=m.sourceTurnId?.let(::mapped)))}
    eachLine(dir,"memory.jsonl"){line->val m=decode<MemoryItem>(line);m.supersedesId?.let{require(db.dao().memory(newId,mapped(it))!=null){"DANGLING_CORRECTION"}}}
    eachLine(dir,"summaries.jsonl"){line->val s=decode<SummaryRow>(line);require(s.campaignId==original.id && s.fromSeq>=1 && s.toSeq<=seq && s.toSeq>=s.fromSeq);db.dao().insertSummary(s.copy(id=mapped(s.id),campaignId=newId,modelFingerprint=""))}
    eachLine(dir,"summary-sources.jsonl"){line->val s=decode<SummarySourceRow>(line);require(s.campaignId==original.id);db.dao().insertSummarySources(listOf(s.copy(summaryId=mapped(s.summaryId),campaignId=newId)))}
    eachLine(dir,"corrections.jsonl"){line->val c=decode<CorrectionRow>(line);require(c.campaignId==original.id && c.sourceRevision<=original.revision);db.dao().insertCorrection(c.copy(id=mapped(c.id),campaignId=newId,oldMemoryId=mapped(c.oldMemoryId),newMemoryId=mapped(c.newMemoryId)))}
    eachLine(dir,"claims.jsonl"){line->val c=decode<ClaimRow>(line);require(c.campaignId==original.id && c.sourceEventSeq in 1..seq && com.ai.assistance.operit.rpg.agent.rules.validVisibility(Visibility(Audience.valueOf(c.audience),c.subjectId)));db.dao().insertClaim(c.copy(id=mapped(c.id),campaignId=newId,supersedesId=c.supersedesId?.let(::mapped)))}
    eachLine(dir,"jobs.jsonl"){line->val j=decode<DerivedJobRow>(line);require(j.campaignId==original.id && j.toSeq<=seq);db.dao().insertJob(j.copy(id=mapped(j.id),campaignId=newId,state=if(j.state=="RUNNING")"INTERRUPTED" else j.state))}
    eachLine(dir,"documents.jsonl"){line->val d=decode<DocumentRow>(line);require(com.ai.assistance.operit.rpg.agent.rules.validVisibility(Visibility(Audience.valueOf(d.audience),d.subjectId)));db.dao().insertDocument(d.copy(id=mapped(d.id)))}
    eachLine(dir,"document-parts.jsonl"){line->val p=decode<DocumentPartRow>(line);require(p.text.toByteArray(Charsets.UTF_8).size<=64*1024);db.dao().insertDocumentParts(listOf(p.copy(documentId=mapped(p.documentId))))}
    eachLine(dir,"documents.jsonl"){line->val d=decode<DocumentRow>(line);val id=mapped(d.id);require(sha256(ReferenceDocumentService(db).read(id))==d.hash);ReferenceDocumentService(db).attach(newId,id)}
    CampaignRef(LONGFORM_ENGINE,newId)
   }
  } finally {dir.deleteRecursively()}
 }
 private fun createStage():File { require(tempDirectory.isDirectory || tempDirectory.mkdirs());return File(tempDirectory,"rpg-backup-${UUID.randomUUID()}").also{require(it.mkdir())} }
 private fun write(dir:File,name:String,text:String)=File(dir,name).writeText(text,Charsets.UTF_8)
 private fun read(dir:File,name:String):String=InputStreamReader(File(dir,name).inputStream(),Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).use{it.readText()}
 private fun lines(dir:File,name:String,block:((String)->Unit)->Unit)=File(dir,name).bufferedWriter(Charsets.UTF_8).use{w->block{line->require(line.toByteArray(Charsets.UTF_8).size<=1024*1024){"BACKUP_LINE_LIMIT"};w.write(line);w.newLine()}}
 private fun eachLine(dir:File,name:String,block:(String)->Unit) {
  val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
  InputStreamReader(File(dir,name).inputStream(),decoder).buffered().use { reader->
   val line=StringBuilder();var bytes=0
   while(true){val ch=reader.read();if(ch<0){if(line.isNotEmpty())block(line.toString());break};if(ch==10){block(line.toString());line.setLength(0);bytes=0}else{line.append(ch.toChar());bytes+=when{Character.isHighSurrogate(ch.toChar())->4;Character.isLowSurrogate(ch.toChar())->0;ch>0x7ff->3;ch>0x7f->2;else->1};require(bytes<=1024*1024){"BACKUP_LINE_LIMIT"}}}
  }
 }
 private fun digest(file:File):String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{stream->val buf=ByteArray(32768);while(true){val n=stream.read(buf);if(n<0)break;hash.update(buf,0,n)}};return hash.digest().joinToString(""){"%02x".format(it)}}
 private fun unzip(input:InputStream,dir:File)=AgentArchiveStreams.extract(input,dir,names)
}
