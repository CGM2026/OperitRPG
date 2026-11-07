package com.ai.assistance.operit.rpg.agent.module

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.*
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

@Serializable data class ModuleDraft(val moduleId:String,val version:String,val title:String,val author:String,val description:String,val world:ModuleWorld,val rules:ModuleRules,val sources:Map<String,String>)
object ModuleValidator {
    /** The draft never supplies file hashes. Source hashes/effect references are normalized by this validator. */
    fun validateDraft(text:String):ValidatedModule {
        val draft=StrictJson.decode<ModuleDraft>(text)
        require(draft.sources.isNotEmpty() && draft.sources.keys.all { it.matches(Regex("module/sources/[A-Za-z0-9_-]+\\.(md|txt)")) }) { "INVALID_DRAFT_SOURCE" }
        fun normalize(s:SourceRef):SourceRef { val source=requireNotNull(draft.sources[s.sourceId]) { "DRAFT_SOURCE_MISSING" }; return s.copy(sourceHash=digest(source)) }
        val evidence=draft.rules.evidence.map { e -> val source=normalize(e.source); require(draft.sources.getValue(source.sourceId).contains(e.quote) && e.quote.isNotBlank()); e.copy(source=source) }
        fun effect(e:Effect)=e.copy(source=normalize(e.source))
        val world=draft.world.copy(facts=draft.world.facts.map { it.copy(source=normalize(it.source)) },actions=draft.world.actions.map { a -> a.copy(successEffects=a.successEffects.map(::effect),failureEffects=a.failureEffects.map(::effect)) })
        val rules=draft.rules.copy(evidence=evidence)
        val files=(draft.sources+mapOf("module/world.json" to StrictJson.json.encodeToString(world),"module/rules.json" to StrictJson.json.encodeToString(rules)))
        val manifest=ModuleManifest(1,draft.moduleId,draft.version,draft.title,draft.author,draft.description,1,rules.adapterId,rules.adapterVersion,files.toSortedMap().map { FileDigest(it.key,it.value.toByteArray().size.toLong(),digest(it.value)) })
        require(manifest.moduleId.matches(Regex("[a-z][a-z0-9-]{2,63}")))
        val module=ValidatedModule(manifest,digest(StrictJson.json.encodeToString(manifest)),world,rules,draft.sources,files)
        RuleAdapters.forModule(module).validateModule(module); return module
    }
}

/** Validation is side effect free. Call ModuleCatalog.install only after explicit preview acceptance. */
class ModuleZipImporter {
    fun validate(input:InputStream):ValidatedModule {
        val archive=readLimited(input,20*1024*1024)
        rejectSymlinks(archive)
        val files=linkedMapOf<String,ByteArray>(); var total=0
        ZipInputStream(archive.inputStream(),Charsets.UTF_8).use { zip ->
            var entries=0
            while(true) {
                val e=zip.nextEntry?:break
                require(++entries<=2000) { "ZIP_ENTRY_LIMIT" }
                val path=e.name
                require(!e.isDirectory && safePath(path)) { "ZIP_PATH_REJECTED" }
                require(path !in files) { "ZIP_DUPLICATE_PATH" }
                val data=readLimited(zip,5*1024*1024)
                total+=data.size; require(total<=50*1024*1024) { "ZIP_EXPANDED_LIMIT" }
                files[path]=data
                zip.closeEntry() // ZipInputStream verifies CRC against actual uncompressed bytes.
            }
        }
        require(files.keys.containsAll(setOf("module/manifest.json","module/world.json","module/rules.json"))) { "MODULE_FILE_MISSING" }
        val text=files.mapValues { decodeUtf8(it.value) }
        val manifest=StrictJson.decode<ModuleManifest>(text.getValue("module/manifest.json"))
        require(manifest.formatVersion==1 && manifest.engineApiVersion==1) { "UNSUPPORTED_MODULE_VERSION" }
        require(manifest.moduleId.matches(Regex("[a-z][a-z0-9-]{2,63}")) && manifest.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "INVALID_MODULE_ID" }
        require(manifest.files.map { it.path }.distinct().size==manifest.files.size)
        require(manifest.files.map { it.path }.toSet()==files.keys-setOf("module/manifest.json")) { "MANIFEST_FILE_MISMATCH" }
        manifest.files.forEach { entry -> val bytes=files.getValue(entry.path); require(bytes.size.toLong()==entry.size && sha256(bytes)==entry.sha256) { "MODULE_HASH_MISMATCH" } }
        val world=StrictJson.decode<ModuleWorld>(text.getValue("module/world.json"))
        val rules=StrictJson.decode<ModuleRules>(text.getValue("module/rules.json"))
        require(manifest.rulesetId==rules.adapterId && manifest.rulesetVersion==rules.adapterVersion) { "RULE_VERSION_MISMATCH" }
        val normalized=manifest.copy(files=manifest.files.sortedBy { it.path })
        val module=ValidatedModule(normalized,digest(StrictJson.json.encodeToString(normalized)),world,rules,text.filterKeys { it.startsWith("module/sources/") },text-setOf("module/manifest.json"))
        rules.evidence.forEach { e ->
            require(validVisibility(e.visibility)) { "INVALID_VISIBILITY" }
            val source=files[e.source.sourceId]; require(source!=null && sha256(source)==e.source.sourceHash) { "SOURCE_HASH_MISMATCH" }
            require(e.source.anchor.isNotBlank() && decodeUtf8(source).contains(e.quote) && e.quote.isNotBlank()) { "INVALID_RULE_QUOTE" }
        }
        RuleAdapters.forModule(module).validateModule(module)
        return module
    }
    private fun safePath(path:String):Boolean {
        if(path.startsWith('/') || path.contains('\\') || path.contains(':') || path.split('/').any { it.isEmpty() || it=="." || it==".." })return false
        return path in setOf("module/manifest.json","module/world.json","module/rules.json") || path.matches(Regex("module/sources/[A-Za-z0-9_-]+\\.(md|txt)"))
    }
    private fun decodeUtf8(bytes:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun readLimited(input:InputStream,limit:Int):ByteArray { val output=ByteArrayOutputStream(); val buffer=ByteArray(8192); while(true) { val n=input.read(buffer); if(n<0)break; require(output.size()+n<=limit) { "ZIP_SIZE_LIMIT" }; output.write(buffer,0,n) }; return output.toByteArray() }
    private fun rejectSymlinks(bytes:ByteArray) {
        fun u16(i:Int)=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
        var i=0
        while(i+46<=bytes.size) {
            if(u16(i)==0x4b50 && u16(i+2)==0x0201) {
                val mode=u16(i+40); require(mode and 0xf000 != 0xa000) { "ZIP_SYMLINK_REJECTED" }
                i+=46+u16(i+28)+u16(i+30)+u16(i+32)
            } else i++
        }
    }
}
