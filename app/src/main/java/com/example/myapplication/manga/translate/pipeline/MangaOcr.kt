package com.example.myapplication.manga.translate.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Распознаёт японский текст блока манги: manga-ocr (kha-white, Apache-2.0) в виде ONNX int8.
 * Кодировщик (ViT) превращает вырезку 224x224 в 197 векторов, декодер (BERT-подобный) по одному
 * токену выписывает текст. Подходит и для вертикального набора, и для рукописных шрифтов.
 *
 * Декодер здесь без кэша ключей/значений: тексту реплики хватает десятка-другого токенов, а
 * вариант без кэша не требует хранить состояние между шагами и не ломается от смены модели.
 */
class MangaOcr(
    private val environment: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val vocabulary: List<String>,
) : AutoCloseable {

    fun recognize(block: RgbImage): String {
        val hidden = encode(block)
        try {
            val ids = ArrayList<Long>()
            ids += START_TOKEN
            while (ids.size < MAX_TOKENS) {
                val next = nextToken(hidden, ids)
                if (next == END_TOKEN) break
                ids += next
                if (isRunaway(ids)) {
                    // Модель зациклилась на одном слоге: убираем повтор и заканчиваем.
                    repeat(RUNAWAY_REPEATS - 1) { ids.removeAt(ids.lastIndex) }
                    break
                }
            }
            return tidy(ids.drop(1).map { it.toInt() })
        } finally {
            hidden.close()
        }
    }

    private fun encode(block: RgbImage): OnnxTensor {
        val input = block.resized(INPUT_SIZE, INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            // Модель училась на серых вырезках, поэтому все три канала - яркость.
            val p = input.pixels[i]
            val gray = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
            val v = (gray / 255f - 0.5f) / 0.5f
            data[i] = v
            data[plane + i] = v
            data[2 * plane + i] = v
        }
        OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(data), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { pixelValues ->
            encoder.run(mapOf("pixel_values" to pixelValues)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val states = result[0].value as Array<Array<FloatArray>>
                val tokens = states[0].size
                val width = states[0][0].size
                val flat = FloatArray(tokens * width)
                for (t in 0 until tokens) System.arraycopy(states[0][t], 0, flat, t * width, width)
                return OnnxTensor.createTensor(
                    environment, FloatBuffer.wrap(flat), longArrayOf(1, tokens.toLong(), width.toLong()),
                )
            }
        }
    }

    private fun nextToken(hidden: OnnxTensor, ids: List<Long>): Long {
        OnnxTensor.createTensor(
            environment, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()),
        ).use { inputIds ->
            decoder.run(mapOf("input_ids" to inputIds, "encoder_hidden_states" to hidden)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                val last = logits[logits.lastIndex]
                var best = 0
                for (i in 1 until last.size) if (last[i] > last[best]) best = i
                return best.toLong()
            }
        }
    }

    private fun isRunaway(ids: List<Long>): Boolean {
        if (ids.size < RUNAWAY_REPEATS + 1) return false
        val tail = ids[ids.lastIndex]
        return (1..RUNAWAY_REPEATS).all { ids[ids.size - it] == tail }
    }

    /** Токены -> строка. Служебные токены (id до 4) и "##" у составных слов отбрасываются. */
    private fun tidy(tokens: List<Int>): String {
        val raw = tokens.filter { it > LAST_SPECIAL_TOKEN && it < vocabulary.size }
            .joinToString("") { vocabulary[it].removePrefix("##") }
        return normalize(raw)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        const val INPUT_SIZE = 224
        private const val START_TOKEN = 2L
        private const val END_TOKEN = 3L
        private const val LAST_SPECIAL_TOKEN = 4
        private const val MAX_TOKENS = 100
        private const val RUNAWAY_REPEATS = 5

        /**
         * NFKC приводит полноширинные "！？" и "ＡＢＣ" к обычным, а многоточие - к трём точкам; ряды
         * "・" (так манга рисует паузы) становятся многоточием, лишние пробелы уходят.
         */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(Regex("[・.]{2,}"), "...")
                .replace(Regex("\\s+"), "")
                .trim()

        fun loadVocabulary(lines: List<String>): List<String> = lines.map { it.trimEnd('\r', '\n') }
    }
}
