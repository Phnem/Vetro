package com.example.myapplication.domain.titleidentity

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

/** Numeric LightGBM inference, with no native library or Android dependency. */
class PortableTitleForest(bytes: ByteArray, private val slope: Double, private val intercept: Double) {
    private data class Tree(val feature: ShortArray, val threshold: DoubleArray, val left: ShortArray, val right: ShortArray, val leaf: DoubleArray)
    private val trees: List<Tree>
    init {
        require(bytes.size in 20..1_500_000 && slope.isFinite() && slope > 0 && intercept.isFinite())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8); buffer.get(magic)
        require(magic.toString(Charsets.US_ASCII) == "VTMF0001")
        require(buffer.int == TitleMatchFeatures.names.size)
        val count = buffer.int; require(count in 1..5000)
        var total = 0
        trees = List(count) {
            val size = buffer.int; total += size
            require(size in 1..32767 && total <= 100_000 && buffer.remaining() >= size * 24)
            val tree = Tree(ShortArray(size), DoubleArray(size), ShortArray(size), ShortArray(size), DoubleArray(size))
            repeat(size) { i ->
                tree.feature[i] = buffer.short; require(buffer.short.toInt() == 0)
                tree.threshold[i] = buffer.double; tree.left[i] = buffer.short; tree.right[i] = buffer.short; tree.leaf[i] = buffer.double
                val feature = tree.feature[i].toInt()
                require(tree.threshold[i].isFinite() && tree.leaf[i].isFinite())
                require(feature == -1 || (feature in TitleMatchFeatures.names.indices && tree.left[i].toInt() in 0 until size && tree.right[i].toInt() in 0 until size))
            }
            tree
        }
        require(!buffer.hasRemaining())
    }
    fun rawProbability(features: FloatArray): Double {
        require(features.size == TitleMatchFeatures.names.size && features.all(Float::isFinite))
        var sum = 0.0
        for (tree in trees) {
            var index = 0; var steps = 0
            while (tree.feature[index].toInt() != -1) {
                require(steps++ < tree.feature.size) { "Cyclic model tree" }
                val feature = tree.feature[index].toInt()
                index = (if (features[feature] <= tree.threshold[index]) tree.left[index] else tree.right[index]).toInt()
            }
            sum += tree.leaf[index]
        }
        return sigmoid(sum)
    }
    fun probability(features: FloatArray): Double {
        val raw = rawProbability(features).coerceIn(1e-7, 1 - 1e-7)
        return sigmoid(slope * ln(raw / (1 - raw)) + intercept)
    }
    private fun sigmoid(value: Double): Double = 1 / (1 + exp(-value.coerceIn(-700.0, 700.0)))
}
