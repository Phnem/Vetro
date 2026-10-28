package com.example.myapplication.domain.titleidentity

import android.content.res.AssetManager
import org.json.JSONObject
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class TitleMatchRuntime(val matcher: TitleIdentityMatcher, val forest: PortableTitleForest, val version: String, val aliases: TitleAliasLookup)

/** Call on a worker thread. Missing/corrupt assets leave callers on their existing fallback. */
object TitleMatchPack {
    fun load(assets: AssetManager, root: String = "titlematch/v1"): TitleMatchRuntime? =
        runCatching { loadVerified(assets, root) }.getOrNull()

    fun loadVerified(assets: AssetManager, root: String = "titlematch/v1"): TitleMatchRuntime {
        val manifest = assets.open("$root/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schema_version") == 1 && manifest.getString("feature_version") == TitleMatchFeatures.VERSION)
        require(manifest.getJSONArray("feature_names").let { a -> (0 until a.length()).map(a::getString) } == TitleMatchFeatures.names)
        val version = manifest.getString("model_version")
        val files = manifest.getJSONObject("files")
        fun verified(name: String): ByteArray {
            val spec = files.getJSONObject(name); val size = spec.getInt("bytes")
            require(size in 1..20_000_000)
            val bytes = assets.open("$root/$name").use { input -> input.readBytes() }
            require(bytes.size == size)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == spec.getString("sha256"))
            return bytes
        }
        val calibration = manifest.getJSONObject("calibration")
        val forest = PortableTitleForest(verified("model.vtmf"), calibration.getDouble("slope"), calibration.getDouble("intercept"))
        val titles = HashMap<String, MutableSet<String>>()
        val ids = HashMap<String, MutableSet<String>>()
        val entities = HashSet<String>()
        fun rows(name: String, consume: (List<String>) -> Unit) {
            GZIPInputStream(verified(name).inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line -> require(line.length <= 8192); consume(line.split('\t')) }
            }
        }
        rows("entities.bin") { require(it.size == 4); entities.add(it[0]) }
        rows("aliases.bin") { require(it.size == 3 && it[2] in entities); titles.getOrPut("${it[0]}:${it[1]}") { HashSet() }.add(it[2]) }
        rows("ids.bin") { require(it.size == 2 && it[1] in entities); ids.getOrPut(it[0]) { HashSet() }.add(it[1]) }
        val lookup = TitleAliasLookup { record ->
            val fromIds = buildSet {
                for (id in record.ids) {
                    if (id.namespace == TitleNamespace.WIKIDATA) "wd:${id.value}:${record.kind}".takeIf { it in entities }?.let(::add)
                    else addAll(ids[id.key].orEmpty())
                }
            }
            // An unknown source ID must not acquire a known identity solely from a homonymous title.
            if (record.ids.isNotEmpty() && fromIds.isEmpty()) emptySet() else buildSet {
                addAll(fromIds)
                for (title in record.titles) addAll(titles["${record.kind}:${TitleMatchFeatures.key(title)}"].orEmpty())
            }
        }
        val scorer = TitlePairScorer { a, b, left, right -> forest.probability(TitleMatchFeatures.compute(a, b, left.kind.name, right.kind.name, left.year, right.year)) }
        val threshold = manifest.getDouble("threshold"); require(threshold in 0.0..1.000001)
        return TitleMatchRuntime(TitleIdentityMatcher(lookup, scorer, threshold, version), forest, version, lookup)
    }
}
