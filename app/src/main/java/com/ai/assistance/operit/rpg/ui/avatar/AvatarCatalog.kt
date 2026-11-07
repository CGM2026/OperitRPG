package com.ai.assistance.operit.rpg.ui.avatar

import android.content.Context
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class AvatarAnimal(
    val id: String,
    val name: String,
    val category: String,
    val categoryName: String,
    val path: String,
)

@Serializable
data class AvatarMotif(val id: String, val name: String, val path: String)

@Serializable
data class AvatarPair(
    val id: String,
    val name: String,
    val mood: String,
    val tone: String,
    val bg: String,
    val line: String,
    val source: String,
    val contrast: Float,
)

@Serializable
private data class AvatarCatalogJson(
    val version: Int,
    val viewBox: String,
    val inkScale: Float,
    val note: String = "",
    val animals: List<AvatarAnimal>,
    val motifs: List<AvatarMotif>,
    val pairs: List<AvatarPair>,
)

/**
 * Immutable avatar catalog baked into the app assets.
 *
 * Parsing is strict: unknown JSON fields, wrong counts, duplicate ids, non `#RRGGBB` colors and
 * empty paths are rejected instead of being repaired.
 */
class AvatarCatalog private constructor(
    val version: Int,
    val viewBox: String,
    val inkScale: Float,
    val animals: List<AvatarAnimal>,
    val motifs: List<AvatarMotif>,
    val pairs: List<AvatarPair>,
) {
    private val animalsById = animals.associateBy { it.id }
    private val motifsById = motifs.associateBy { it.id }
    private val pairsById = pairs.associateBy { it.id }

    init {
        require(animals.size == ANIMAL_COUNT) { "avatar catalog must contain $ANIMAL_COUNT animals, got ${animals.size}" }
        require(motifs.size == MOTIF_COUNT) { "avatar catalog must contain $MOTIF_COUNT motifs, got ${motifs.size}" }
        require(pairs.size == PAIR_COUNT) { "avatar catalog must contain $PAIR_COUNT pairs, got ${pairs.size}" }
        requireUnique(animals.map { it.id }, "animal")
        requireUnique(motifs.map { it.id }, "motif")
        requireUnique(pairs.map { it.id }, "pair")
        animals.forEach {
            require(it.path.isNotBlank()) { "animal ${it.id} has an empty path" }
        }
        motifs.forEach {
            require(it.path.isNotBlank()) { "motif ${it.id} has an empty path" }
        }
        pairs.forEach {
            require(COLOR.matches(it.bg)) { "pair ${it.id} has invalid bg color ${it.bg}" }
            require(COLOR.matches(it.line)) { "pair ${it.id} has invalid line color ${it.line}" }
        }
    }

    /** Same seed always yields the same glyph and pair. */
    fun pick(kind: AvatarKind, seed: String): AvatarSpec {
        val h = seedHash(seed)
        val glyphId = when (kind) {
            AvatarKind.CHARACTER -> animals[Math.floorMod(h, animals.size.toLong()).toInt()].id
            AvatarKind.CAMPAIGN -> motifs[Math.floorMod(h, motifs.size.toLong()).toInt()].id
        }
        return AvatarSpec(
            kind = kind,
            glyphId = glyphId,
            pairId = pairs[Math.floorMod(h ushr 32, pairs.size.toLong()).toInt()].id,
        )
    }

    fun glyphPath(kind: AvatarKind, glyphId: String): String = when (kind) {
        AvatarKind.CHARACTER -> requireNotNull(animalsById[glyphId]) { "unknown animal $glyphId" }.path
        AvatarKind.CAMPAIGN -> requireNotNull(motifsById[glyphId]) { "unknown motif $glyphId" }.path
    }

    fun pair(pairId: String): AvatarPair =
        requireNotNull(pairsById[pairId]) { "unknown avatar pair $pairId" }

    companion object {
        private const val ANIMAL_COUNT = 24
        private const val MOTIF_COUNT = 12
        private const val PAIR_COUNT = 24
        private const val ASSET = "avatars/avatars.json"
        private val COLOR = Regex("#[0-9A-Fa-f]{6}")
        private val decoder = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }

        @Volatile
        private var loaded: AvatarCatalog? = null

        fun parse(json: String): AvatarCatalog {
            val decoded = decoder.decodeFromString<AvatarCatalogJson>(json)
            return AvatarCatalog(
                version = decoded.version,
                viewBox = decoded.viewBox,
                inkScale = decoded.inkScale,
                animals = decoded.animals,
                motifs = decoded.motifs,
                pairs = decoded.pairs,
            )
        }

        /** Reads and parses the bundled catalog once per process. */
        fun load(context: Context): AvatarCatalog {
            loaded?.let { return it }
            return synchronized(this) {
                loaded ?: parse(
                    context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
                ).also { loaded = it }
            }
        }

        private fun requireUnique(ids: List<String>, label: String) {
            require(ids.size == ids.toSet().size) { "duplicate $label id in avatar catalog" }
        }

        private fun seedHash(seed: String): Long {
            val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            var h = 0L
            for (i in 0 until 8) h = (h shl 8) or (bytes[i].toLong() and 0xFFL)
            return h
        }
    }
}
