package com.example.myapplication.manga.translate.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt

/**
 * Находит на странице облака реплик и блоки текста (внутри облаков и вне них) моделью RT-DETRv2
 * comic-text-and-bubble-detector (Apache-2.0, int8, 11 МБ).
 *
 * Чем это отличается от схемы "детектор строк + склейка строк в облака" у других переводчиков:
 * модель сразу отдаёт БЛОК текста целиком (все колонки одной реплики) и отдельно облако, так что
 * склеивать строки не нужно, а лишние стадии - ошибки и время - отпадают.
 *
 * Модель обучена на картинках 640x640, растянутых без сохранения пропорций, и режет высокие
 * вебтуны на куски. Так делаем и мы: обычная страница уходит целиком, а длинная полоса - кусками
 * с большим нахлёстом, и рамки, упёршиеся в стык кусков, отбрасываются (полная версия есть в
 * соседнем куске).
 */
class TextRegionDetector(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    fun detect(page: RgbImage, minScore: Float = DEFAULT_MIN_SCORE): List<Detection> {
        val tiles = tilesFor(page.width, page.height)
        val all = ArrayList<Detection>()
        for (tile in tiles) {
            val piece = if (tiles.size == 1) page else page.crop(Box(0f, tile.top.toFloat(), page.width.toFloat(), tile.bottom.toFloat()))
            val found = detectTile(piece, minScore)
            for (d in found) {
                // Рамка на внутреннем стыке обрезана; настоящая лежит в соседнем куске.
                val cutAtTop = tile.top > 0 && d.box.top <= SEAM_PX
                val cutAtBottom = tile.bottom < page.height && d.box.bottom >= piece.height - SEAM_PX
                if (cutAtTop || cutAtBottom) continue
                all += d.copy(box = d.box.offset(0f, tile.top.toFloat()))
            }
        }
        return nonMaxSuppression(all, NMS_IOU)
    }

    private fun detectTile(image: RgbImage, minScore: Float): List<Detection> {
        val input = image.resized(INPUT_SIZE, INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = input.pixels[i]
            data[i] = (p shr 16 and 0xFF) / 255f
            data[plane + i] = (p shr 8 and 0xFF) / 255f
            data[2 * plane + i] = (p and 0xFF) / 255f
        }
        val pixelValues = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(data), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        // Размер исходного куска [ширина, высота]: модель сама переводит рамки в его пиксели.
        val sizes = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(longArrayOf(image.width.toLong(), image.height.toLong())), longArrayOf(1, 2),
        )
        pixelValues.use {
            sizes.use {
                session.run(mapOf("images" to pixelValues, "orig_target_sizes" to sizes)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val labels = (result[0].value as Array<LongArray>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val boxes = (result[1].value as Array<Array<FloatArray>>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val scores = (result[2].value as Array<FloatArray>)[0]
                    val out = ArrayList<Detection>()
                    for (i in scores.indices) {
                        if (scores[i] < minScore) continue
                        val kind = DetectionKind.entries.getOrNull(labels[i].toInt()) ?: continue
                        val b = boxes[i]
                        val box = Box(b[0], b[1], b[2], b[3]).clampTo(image.width, image.height)
                        if (box.width < MIN_SIDE_PX || box.height < MIN_SIDE_PX) continue
                        out += Detection(kind, box, scores[i])
                    }
                    return out
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    /** Вертикальный отрезок страницы, который уходит в модель одним куском. */
    data class Tile(val top: Int, val bottom: Int)

    companion object {
        const val INPUT_SIZE = 640
        const val DEFAULT_MIN_SCORE = 0.4f

        private const val NMS_IOU = 0.6f
        private const val MIN_SIDE_PX = 6f
        private const val SEAM_PX = 3f

        /** Страница выше этого отношения высоты к ширине режется на куски. */
        private const val TALL_RATIO = 2.0f

        /** Высота куска в ширинах страницы: близко к пропорциям обычной страницы манги. */
        private const val TILE_HEIGHT_IN_WIDTHS = 1.5f

        /** Нахлёст кусков (в ширинах): больше самого высокого блока текста, чтобы он целиком попал в один. */
        private const val TILE_OVERLAP_IN_WIDTHS = 0.55f

        fun tilesFor(width: Int, height: Int): List<Tile> {
            if (height <= width * TALL_RATIO) return listOf(Tile(0, height))
            val tileHeight = (width * TILE_HEIGHT_IN_WIDTHS).roundToInt()
            val step = (width * (TILE_HEIGHT_IN_WIDTHS - TILE_OVERLAP_IN_WIDTHS)).roundToInt().coerceAtLeast(1)
            val tiles = ArrayList<Tile>()
            var top = 0
            while (true) {
                val bottom = minOf(height, top + tileHeight)
                tiles += Tile(top, bottom)
                if (bottom >= height) break
                top += step
            }
            // Хвост короче трети куска не стоит отдельного прогона: сдвигаем последний кусок вверх.
            if (tiles.size > 1 && tiles.last().bottom - tiles.last().top < tileHeight / 3) {
                tiles[tiles.lastIndex] = Tile(maxOf(0, height - tileHeight), height)
            }
            return tiles
        }
    }
}
