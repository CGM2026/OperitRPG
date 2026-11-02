package com.ai.assistance.operit.rpg.agent.data

import java.io.*
import java.util.zip.ZipInputStream

/** Fixed-name extraction: an archive never chooses a filesystem destination. */
object AgentArchiveStreams {
 data class Limits(val compressed:Long=100L*1024*1024,val expanded:Long=250L*1024*1024,val file:Long=200L*1024*1024)
 fun extract(input:InputStream,dir:File,files:Set<String>,limits:Limits=Limits()) {
  val names=files+"manifest.json"
  require(names.all{it.matches(Regex("[a-z-]+\\.(json|jsonl)"))})
  var compressed=0L
  val counted=object:FilterInputStream(input){
   override fun read():Int=super.read().also{if(it>=0){compressed++;require(compressed<=limits.compressed){"BACKUP_COMPRESSED_LIMIT"}}}
   override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also{if(it>0){compressed+=it;require(compressed<=limits.compressed){"BACKUP_COMPRESSED_LIMIT"}}}
  }
  val seen=mutableSetOf<String>();var total=0L
  ZipInputStream(BufferedInputStream(counted)).use { zip->while(true){val entry=zip.nextEntry?:break
   require(!entry.isDirectory && entry.name in names && seen.add(entry.name)){"INVALID_BACKUP_ENTRY"}
   var fileSize=0L
   File(dir,entry.name).outputStream().use { out->
    val buffer=ByteArray(32768)
    while(true){val n=zip.read(buffer);if(n<0)break;fileSize+=n;total+=n
     require(total<=limits.expanded && fileSize<=limits.file && (entry.name!="manifest.json" || fileSize<=65536)){"BACKUP_EXPANDED_LIMIT"}
     out.write(buffer,0,n)
    }
   }
   zip.closeEntry()
  }
   // Enforce compressed size on trailing ZIP metadata and appended bytes as well.
   val remainder=ByteArray(32768);while(counted.read(remainder)>=0){}
  }
  require(seen==names){"BACKUP_MISSING_ENTRY"}
 }
}
