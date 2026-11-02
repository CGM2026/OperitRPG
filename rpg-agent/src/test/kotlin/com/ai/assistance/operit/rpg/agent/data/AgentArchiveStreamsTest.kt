package com.ai.assistance.operit.rpg.agent.data

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AgentArchiveStreamsTest {
 private fun archive(entries:List<Pair<String,String>>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->entries.forEach{(name,text)->zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}}}.toByteArray()
 private fun stage(block:(File)->Unit){val dir=Files.createTempDirectory("agent-archive-test").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
 private fun rejected(block:()->Unit){var failure:Exception?=null;try{block()}catch(e:Exception){failure=e};assertNotNull("expected rejection",failure)}
 @Test fun fixedNamesStreamWithoutExtractingAnAttackerPath()=stage{dir->
  val bytes=archive(listOf("manifest.json" to "{}","campaign.json" to "正文"))
  AgentArchiveStreams.extract(bytes.inputStream(),dir,setOf("campaign.json"))
  assertEquals("正文",File(dir,"campaign.json").readText())
  rejected{AgentArchiveStreams.extract(archive(listOf("../escape.json" to "bad")).inputStream(),dir,setOf("campaign.json"))}
  rejected{AgentArchiveStreams.extract(archive(listOf("/absolute.json" to "bad")).inputStream(),dir,setOf("campaign.json"))}
 }
 @Test fun actualBytesEnforceExpandedAndCompressedLimits()=stage{dir->
  val bytes=archive(listOf("manifest.json" to "{}","campaign.json" to "x".repeat(20000)))
  rejected{AgentArchiveStreams.extract(bytes.inputStream(),dir,setOf("campaign.json"),AgentArchiveStreams.Limits(expanded=1000))}
  rejected{AgentArchiveStreams.extract(bytes.inputStream(),dir,setOf("campaign.json"),AgentArchiveStreams.Limits(compressed=10))}
 }
 @Test fun missingAndUnknownEntriesAreRejected()=stage{dir->
  rejected{AgentArchiveStreams.extract(archive(listOf("manifest.json" to "{}")).inputStream(),dir,setOf("campaign.json"))}
  rejected{AgentArchiveStreams.extract(archive(listOf("manifest.json" to "{}","script.json" to "{}")).inputStream(),dir,setOf("campaign.json"))}
 }
}
