package com.example.myapplication.audiobooks.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.phnem.vetro.R
import java.util.Locale

/**
 * Что книга показывает системе: карточке медиа на экране блокировки, в шторке и на часах.
 *
 * Системная карточка берёт из сессии две строки — `DISPLAY_TITLE` (иначе `TITLE`) и `ARTIST` — и
 * обложку. В плеере приложения у этих полей другой смысл: `title` — глава, `artist` исторически —
 * автор. Поэтому название книги уходит в `displayTitle`, а `artist` становится строкой для
 * системы («Автор · Глава 3»); сам автор хранится в extras под [AUTHOR].
 */
internal object AudiobookMediaText {
    const val AUTHOR = "author"

    /** Автор книги: extras новых элементов, `artist` — у сохранённых до этого очередей. */
    fun author(metadata: MediaMetadata): String =
        metadata.extras?.getString(AUTHOR) ?: metadata.artist?.toString().orEmpty()

    /** Вторая строка системной карточки: кто написал (или читает) и какая сейчас глава. */
    fun systemLine(chapterTitle: String?, author: String, narrator: String): String? =
        listOfNotNull(author.ifBlank { narrator }.takeIf { it.isNotBlank() }, chapterLabel(chapterTitle))
            .joinToString(" · ")
            .ifBlank { null }

    /**
     * Название главы для людей. Файлы локальных книг часто называются «01», «02» — голый номер в
     * карточке выглядит мусором, поэтому он превращается в «Часть 1».
     */
    fun chapterLabel(title: String?): String? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val number = trimmed.toIntOrNull() ?: return trimmed
        return if (Locale.getDefault().language == "ru") "Часть $number" else "Part $number"
    }
}

/**
 * Загрузчик обложки для сессии. У книги без обложки (локальная папка без картинки, сайт без
 * постера) системная карточка иначе остаётся пустой серой плашкой — отдаём брендовую заглушку.
 * Обложка, вшитая в сам файл, приходит в метаданных потока и выигрывает у заглушки.
 */
@UnstableApi
internal class AudiobookArtworkLoader(private val context: Context) : BitmapLoader {
    private val delegate: BitmapLoader = CacheBitmapLoader(DataSourceBitmapLoader(context))
    private var placeholder: Bitmap? = null

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = delegate.loadBitmap(uri)

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
        val real = delegate.loadBitmapFromMetadata(metadata) ?: return Futures.immediateFuture(placeholder())
        // Битая ссылка на обложку (сайт убрал картинку) — тоже заглушка, а не пустая карточка.
        val result = SettableFuture.create<Bitmap>()
        Futures.addCallback(real, object : FutureCallback<Bitmap> {
            override fun onSuccess(bitmap: Bitmap) { result.set(bitmap) }
            override fun onFailure(t: Throwable) { result.set(placeholder()) }
        }, MoreExecutors.directExecutor())
        return result
    }

    /**
     * Брендовая обложка без текста: система рисует название поверх картинки, и надпись в самой
     * обложке спорила бы с ним. Оранжевый уходит в чёрный, по центру — наушники.
     */
    @Synchronized
    private fun placeholder(): Bitmap = placeholder ?: run {
        val size = PLACEHOLDER_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = size.toFloat()
        canvas.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(0f, 0f, s, s, BRAND_ORANGE, BRAND_DARK, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, s, s, Paint().apply {
            shader = RadialGradient(s * 0.3f, s * 0.25f, s * 0.7f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        ContextCompat.getDrawable(context, R.drawable.ph_headphones)?.mutate()?.let { icon ->
            val iconSize = (s * 0.36f).toInt()
            val left = (size - iconSize) / 2
            icon.setBounds(left, left, left + iconSize, left + iconSize)
            icon.setTint(0xE6FFFFFF.toInt())
            icon.draw(canvas)
        }
        bitmap.also { placeholder = it }
    }

    private companion object {
        const val PLACEHOLDER_SIZE = 512
        const val BRAND_ORANGE = 0xFFE85002.toInt()
        const val BRAND_DARK = 0xFF121212.toInt()
    }
}

/**
 * Уведомление плеера: своя монохромная иконка вместо значка Media3 и те же строки, что у
 * системной карточки (название книги + «Автор · Глава»), а не номер файла.
 */
@UnstableApi
internal class AudiobookNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {
    init {
        setSmallIcon(R.drawable.ic_launcher_monochrome)
    }

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? =
        metadata.displayTitle ?: metadata.title

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? = metadata.artist
}
