package com.example.myapplication.media.subtitles

import com.example.myapplication.data.models.MediaType
import com.example.myapplication.media.source.PlaybackIdentity
import com.example.myapplication.media.source.PlaybackSourceConfigStore
import com.example.myapplication.media.source.UserAccountKind
import com.example.myapplication.media.source.VetroSubtitleTrack
import com.example.myapplication.network.LookupResult
import com.example.myapplication.network.enrichment.EnrichmentHttpException
import com.example.myapplication.network.enrichment.OpenSubtitlesClient
import com.example.myapplication.network.enrichment.OpenSubtitlesSession
import com.example.myapplication.network.enrichment.SubtitleCandidate
import com.example.myapplication.network.enrichment.SubtitleQuery
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Субтитры из OpenSubtitles, которые можно подгрузить к текущему видео. */
data class SubtitleOffer(
    val fileId: Long,
    /** ISO 639-1. */
    val language: String,
    val release: String?,
    val hearingImpaired: Boolean,
)

sealed interface SubtitleLoad {
    data class Loaded(val track: VetroSubtitleTrack) : SubtitleLoad
    /** Логин или пароль больше не подходят — поправить в настройках. */
    data object AccountRejected : SubtitleLoad
    /** Суточный лимит скачиваний аккаунта исчерпан. */
    data object QuotaExhausted : SubtitleLoad
    data object Failed : SubtitleLoad
}

/**
 * Поиск — ключом приложения (кэш сутки, квоту пользователя не тратит); скачивание — токеном
 * пользователя в счёт его лимита. Скачанный файл лежит в `cacheDir/subtitles/os-<file_id>.srt` и
 * повторно не скачивается: ссылка OpenSubtitles живёт 3 часа, а файл — пока его не вычистит система.
 */
class ExternalSubtitleService(
    private val client: OpenSubtitlesClient,
    private val accounts: PlaybackSourceConfigStore,
    private val dir: File,
    /** GET временной ссылки; null — не удалось. */
    private val fetchBytes: suspend (String) -> ByteArray?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val sessionLock = Mutex()
    private var session: Pair<OpenSubtitlesSession, Long>? = null

    val isAvailable: Boolean
        get() = client.isConfigured && accounts.account(UserAccountKind.OPENSUBTITLES) != null

    suspend fun offers(
        identity: PlaybackIdentity,
        season: Int?,
        episode: Int?,
        languages: List<String>,
    ): List<SubtitleOffer> {
        if (!isAvailable || (identity.imdbId == null && identity.tmdbId == null)) return emptyList()
        val movie = identity.mediaType == MediaType.MOVIE
        val query = SubtitleQuery(
            imdbId = identity.imdbId,
            tmdbId = identity.tmdbId,
            season = season.takeUnless { movie },
            episode = episode.takeUnless { movie },
            languages = languages,
        )
        val found = (client.search(query) as? LookupResult.Found)?.value ?: return emptyList()
        return SubtitleRanking.rank(found, languages)
    }

    suspend fun load(offer: SubtitleOffer): SubtitleLoad {
        val file = File(dir, "os-${offer.fileId}.srt")
        if (file.isFile && file.length() > 0) return SubtitleLoad.Loaded(offer.track(file))
        val account = accounts.account(UserAccountKind.OPENSUBTITLES) ?: return SubtitleLoad.AccountRejected
        repeat(2) { attempt ->
            val current = session(account.username, account.password, forceLogin = attempt > 0)
                ?: return SubtitleLoad.AccountRejected
            when (val link = client.downloadLink(offer.fileId, current)) {
                is LookupResult.Found -> {
                    val bytes = fetchBytes(link.value.url)?.takeIf { it.isNotEmpty() && it.size <= MAX_FILE_BYTES }
                        ?: return SubtitleLoad.Failed
                    withContext(Dispatchers.IO) {
                        dir.mkdirs()
                        val tmp = File(dir, "${file.name}.tmp")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(file)
                    }
                    return SubtitleLoad.Loaded(offer.track(file))
                }
                is LookupResult.Failure -> when ((link.cause as? EnrichmentHttpException)?.status) {
                    // Токен протух — один раз входим заново.
                    401 -> if (attempt == 0) return@repeat else return SubtitleLoad.AccountRejected
                    406, 429 -> return SubtitleLoad.QuotaExhausted
                    else -> return SubtitleLoad.Failed
                }
                else -> return SubtitleLoad.Failed
            }
        }
        return SubtitleLoad.Failed
    }

    private suspend fun session(username: String, password: String, forceLogin: Boolean): OpenSubtitlesSession? =
        sessionLock.withLock {
            session?.takeIf { !forceLogin && nowMs() - it.second < SESSION_TTL_MS }?.let { return it.first }
            val fresh = (client.login(username, password) as? LookupResult.Found)?.value
            session = fresh?.let { it to nowMs() }
            fresh
        }

    private fun SubtitleOffer.track(file: File) = VetroSubtitleTrack(
        url = file.toURI().toString(),
        lang = language,
        mimeType = "application/x-subrip",
        id = "opensubtitles:$fileId",
        label = subtitleLabel(this),
    )

    private companion object {
        /** Токен OpenSubtitles живёт сутки; берём с запасом. */
        const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}

/** Порядок выдачи: язык интерфейса первым, в языке — проверенные, без пометок для слабослышащих, популярные. */
internal object SubtitleRanking {
    private const val PER_LANGUAGE = 3

    fun rank(candidates: List<SubtitleCandidate>, languages: List<String>): List<SubtitleOffer> {
        val order = languages.map { it.lowercase() }
        return candidates
            .groupBy { it.language.substringBefore('-') }
            .filterKeys { it in order }
            .toSortedMap(compareBy { order.indexOf(it) })
            .flatMap { (language, list) ->
                // Машинный перевод — только если живого перевода на этом языке нет вовсе.
                val human = list.filterNot { it.machineTranslated || it.aiTranslated }
                (human.ifEmpty { list })
                    .sortedWith(
                        compareByDescending<SubtitleCandidate> { it.fromTrusted }
                            .thenBy { it.hearingImpaired }
                            .thenByDescending { it.downloadCount },
                    )
                    .distinctBy { it.fileId }
                    .take(PER_LANGUAGE)
                    .map { SubtitleOffer(it.fileId, language, it.release, it.hearingImpaired) }
            }
    }
}

/** «Русский · WEB-DL 1080p» — пилюля в меню плеера узкая, релиз обрезается. */
internal fun subtitleLabel(offer: SubtitleOffer): String {
    val lang = Locale.forLanguageTag(offer.language).let { it.getDisplayLanguage(it) }
        .replaceFirstChar { it.uppercase() }
        .ifBlank { offer.language }
    val release = offer.release?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length > 22) it.take(21) + "…" else it
    }
    return listOfNotNull(lang, release, "SDH".takeIf { offer.hearingImpaired }).joinToString(" · ")
}
