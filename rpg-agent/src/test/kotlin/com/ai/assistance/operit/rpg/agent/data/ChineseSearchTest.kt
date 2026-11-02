package com.ai.assistance.operit.rpg.agent.data

import org.junit.Assert.*
import org.junit.Test

class ChineseSearchTest {
 @Test fun basicAndExtensionAHanUsePortableLiteralRanges(){
  assertEquals(listOf("㐀","汉","㐀汉","rule-1"),ChineseSearch.terms("㐀汉 RULE-1"))
  assertEquals(listOf("汉","字"),ChineseSearch.terms("汉䷀字"))
  assertEquals("\"㐀汉\"",ChineseSearch.query("㐀汉"))
 }
 @Test fun chineseBigramsAndSingleCharacterQueriesAreExplicit(){
  assertTrue(ChineseSearch.terms("船工承诺").containsAll(listOf("船","船工","工承","承诺")))
  assertEquals("\"船\"",ChineseSearch.query("船"))
  assertEquals("\"船工\" OR \"工承\" OR \"承诺\"",ChineseSearch.query("船工承诺"))
 }
 @Test fun ftsOperatorsCannotBeInjected(){
  val query=ChineseSearch.query("\" OR * NEAR (秘密) -GM:everything")
  assertFalse(query.contains("*"));assertFalse(query.contains("("));assertTrue(query.contains("\"秘密\""))
 }
 @Test fun utf8PartsNeverSplitSurrogatesAndRespectBound(){
  val source="古书😀".repeat(30_000);val parts=textParts(source)
  assertEquals(source,parts.joinToString(""));assertTrue(parts.all{it.toByteArray(Charsets.UTF_8).size<=64*1024})
  assertTrue(parts.all{!Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first())})
 }
}
