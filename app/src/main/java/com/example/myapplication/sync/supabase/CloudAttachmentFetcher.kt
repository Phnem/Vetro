package com.example.myapplication.sync.supabase

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Path.Companion.toOkioPath

/** Схема ссылок на картинки в R2: `collection-attachment://<attachmentId>`. */
internal const val CLOUD_ATTACHMENT_SCHEME = "collection-attachment"

/**
 * Загрузчик Coil для ссылок на картинки в облаке. Обложки книг синхронизируются между устройствами
 * строкой `cover_url`; для загруженной вручную в ней лежит такая ссылка, а не адрес сайта.
 * Файл берётся из локального кэша синхронизации, а если его там нет (другое устройство, очищенный
 * кэш) — скачивается из R2 и остаётся в кэше.
 */
class CloudAttachmentFetcher(
    private val url: String,
    private val options: Options,
    private val attachments: AttachmentSyncManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = attachments.downloadAttachment(url) ?: return null
        return SourceFetchResult(
            source = ImageSource(file = file.toOkioPath(), fileSystem = options.fileSystem),
            mimeType = "image/webp",
            dataSource = DataSource.DISK,
        )
    }

    /** [attachments] — ленивый доступ: менеджер нужен, только когда встретилась облачная ссылка. */
    class Factory(private val attachments: () -> AttachmentSyncManager) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.scheme == CLOUD_ATTACHMENT_SCHEME) {
                CloudAttachmentFetcher(data.toString(), options, attachments())
            } else {
                null
            }
    }
}
