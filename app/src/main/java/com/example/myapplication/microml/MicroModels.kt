package com.example.myapplication.microml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Публичный контракт любой табличной модели MicroML: вектор признаков → оценки классов.
 * Сегодня за ним стоит лес [MicroForest]; заменить его на другую архитектуру можно, не трогая вызывающий код —
 * формат выбирается по полю манифеста пакета.
 */
interface MicroScorer {
    val features: Int
    val classes: Int
    /** Вероятности (sigmoid/softmax) либо сырые оценки — как объявлено в файле модели. */
    fun predict(x: FloatArray): FloatArray
}

/**
 * Лес решающих деревьев в формате VMLF0002 (tools/microml/export/formats.py). Чистый Kotlin без JNI.
 * Узел — 10 байт, лист — 4: втрое компактнее формата TitleMatch v1 при том же результате.
 */
class MicroForest(bytes: ByteArray) : MicroScorer {
    override val features: Int
    override val classes: Int
    private val link: Int
    private val nodeFeature: Array<ShortArray>
    private val nodeLeft: Array<ShortArray>
    private val nodeRight: Array<ShortArray>
    private val nodeThreshold: Array<FloatArray>
    private val leaves: Array<FloatArray>

    init {
        require(bytes.size in 24..MAX_BYTES) { "forest size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8); buffer.get(magic)
        require(String(magic, Charsets.US_ASCII) == "VMLF0002") { "forest magic" }
        features = buffer.int; classes = buffer.int
        val count = buffer.int; link = buffer.int
        require(features in 1..4096 && classes in 1..64 && count in 1..20_000 && link in 0..2 && count % classes == 0)
        val f = arrayOfNulls<ShortArray>(count); val l = arrayOfNulls<ShortArray>(count)
        val r = arrayOfNulls<ShortArray>(count); val t = arrayOfNulls<FloatArray>(count); val v = arrayOfNulls<FloatArray>(count)
        for (tree in 0 until count) {
            require(buffer.remaining() >= 8)
            val internal = buffer.int; val leafCount = buffer.int
            require(internal in 0..32766 && leafCount == internal + 1 && buffer.remaining() >= internal * 10 + leafCount * 4)
            val ff = ShortArray(internal); val ll = ShortArray(internal); val rr = ShortArray(internal); val tt = FloatArray(internal)
            for (i in 0 until internal) {
                ff[i] = buffer.short; ll[i] = buffer.short; rr[i] = buffer.short; tt[i] = buffer.float
                require(ff[i].toInt() in 0 until features && tt[i].isFinite())
                // Потомок с большим номером: дерево LightGBM растёт вперёд, значит цикл в файле невозможен.
                require(validChild(ll[i].toInt(), i, internal, leafCount) && validChild(rr[i].toInt(), i, internal, leafCount))
            }
            val vv = FloatArray(leafCount) { buffer.float.also { value -> require(value.isFinite()) } }
            f[tree] = ff; l[tree] = ll; r[tree] = rr; t[tree] = tt; v[tree] = vv
        }
        require(!buffer.hasRemaining()) { "forest trailing bytes" }
        nodeFeature = f.requireNoNulls(); nodeLeft = l.requireNoNulls(); nodeRight = r.requireNoNulls()
        nodeThreshold = t.requireNoNulls(); leaves = v.requireNoNulls()
    }

    private fun validChild(child: Int, parent: Int, internal: Int, leafCount: Int): Boolean =
        if (child >= 0) child in (parent + 1) until internal else child.inv() in 0 until leafCount

    fun raw(x: FloatArray): DoubleArray {
        require(x.size == features) { "feature count" }
        val sums = DoubleArray(classes)
        for (tree in leaves.indices) {
            val feature = nodeFeature[tree]
            var node = if (feature.isEmpty()) -1 else 0
            if (node == 0) {
                val left = nodeLeft[tree]; val right = nodeRight[tree]; val threshold = nodeThreshold[tree]
                while (node >= 0) node = (if (x[feature[node].toInt()] <= threshold[node]) left[node] else right[node]).toInt()
            }
            sums[tree % classes] += leaves[tree][node.inv()].toDouble()
        }
        return sums
    }

    override fun predict(x: FloatArray): FloatArray {
        val sums = raw(x)
        return when (link) {
            1 -> FloatArray(classes) { sigmoid(sums[it]).toFloat() }
            2 -> {
                val peak = sums.max()
                var total = 0.0
                val e = DoubleArray(classes) { exp(sums[it] - peak).also { value -> total += value } }
                FloatArray(classes) { (e[it] / total).toFloat() }
            }
            else -> FloatArray(classes) { sums[it].toFloat() }
        }
    }

    companion object {
        const val MAX_BYTES = 16_000_000
        fun sigmoid(value: Double): Double = 1 / (1 + exp(-value.coerceIn(-700.0, 700.0)))
        /** Калибровка Платта поверх сырой вероятности. */
        fun platt(raw: Double, slope: Double, intercept: Double): Double {
            val p = raw.coerceIn(1e-7, 1 - 1e-7)
            return sigmoid(slope * ln(p / (1 - p)) + intercept)
        }
    }
}

/**
 * Хешированная таблица VMLE0001: строка r = q[r] · levels[code[r]] (int8 с построчным масштабом).
 * Одна и та же структура служит энкодером названий (сумма строк → L2-норма)
 * и линейным классификатором (сумма строк + смещение → логиты).
 */
class MicroTable(bytes: ByteArray) {
    val buckets: Int
    val dim: Int
    private val levels = FloatArray(256)
    private val code: ByteArray
    private val q: ByteArray

    init {
        require(bytes.size in HEADER..MAX_BYTES) { "table size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8); buffer.get(magic)
        require(String(magic, Charsets.US_ASCII) == "VMLE0001") { "table magic" }
        buckets = buffer.int; dim = buffer.int
        require(buckets in 2..(1 shl 22) && buckets and (buckets - 1) == 0 && dim in 1..1024)
        require(bytes.size.toLong() == HEADER + buckets.toLong() + buckets.toLong() * dim) { "table length" }
        for (i in 0 until 256) levels[i] = buffer.float.also { require(it.isFinite() && it >= 0f) }
        code = ByteArray(buckets); buffer.get(code)
        q = ByteArray(buckets * dim); buffer.get(q)
    }

    /** Сумма строк таблицы по корзинам [ids] (в порядке возрастания — так же, как при обучении). */
    fun sum(ids: IntArray): FloatArray {
        val out = FloatArray(dim)
        for (id in ids) {
            val scale = levels[code[id].toInt() and 0xFF]
            val base = id * dim
            for (d in 0 until dim) out[d] += q[base + d] * scale
        }
        return out
    }

    /** Вектор названия: L2-нормированная сумма строк. Пустая строка даёт нулевой вектор. */
    fun embed(norm: String): FloatArray {
        val v = sum(MicroText.bucketIds(norm, buckets))
        var total = 0f
        for (value in v) total += value * value
        val length = sqrt(total)
        if (length > 0f) for (d in v.indices) v[d] /= length
        return v
    }

    companion object {
        const val HEADER = 16 + 1024
        const val MAX_BYTES = 64_000_000
        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
