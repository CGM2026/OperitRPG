package com.ai.assistance.operit.rpg.agent.data

import java.security.MessageDigest

/** FTS receives only quoted generated terms, never user-provided MATCH syntax. */
object ChineseSearch {
 // Android API 26 ICU rejects Java's IsHan alias; literal BMP ranges work on both runtimes.
 private val searchable=Regex("[\u3400-\u4DBF\u4E00-\u9FFF]+|[a-zA-Z0-9_:-]+")
 fun terms(text:String):List<String> {
  val out=linkedSetOf<String>()
  searchable.findAll(text.lowercase()).forEach { match->
   val s=match.value
   if(s.first().code in 0x3400..0x4dbf || s.first().code in 0x4e00..0x9fff){s.forEach{out+=it.toString()};s.windowed(2).forEach{out+=it}}else out+=s
  }
  return out.take(256)
 }
 fun index(text:String)=terms(text).joinToString(" ")
 fun query(text:String):String {
  val all=terms(text);val phrases=all.filter{it.length>1}
  return (if(phrases.isEmpty())all else phrases).take(64).joinToString(" OR "){"\"${it.replace("\"","")}\""}.ifEmpty{"\"__empty_query__\""}
 }
}
fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
/** Storage parts are bounded by UTF-8 bytes and never divide a surrogate pair. */
fun textParts(text:String,maxBytes:Int=60*1024):List<String> {
 require(maxBytes>=4)
 val parts=mutableListOf<String>();val current=StringBuilder();var bytes=0;var offset=0
 while(offset<text.length){val cp=text.codePointAt(offset);val unit=String(Character.toChars(cp));val size=unit.toByteArray(Charsets.UTF_8).size
  if(bytes+size>maxBytes){parts+=current.toString();current.setLength(0);bytes=0}
  current.append(unit);bytes+=size;offset+=Character.charCount(cp)
 }
 if(current.isNotEmpty() || parts.isEmpty())parts+=current.toString()
 return parts
}
