package com.example.myapplication.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Связь тайтла коллекции с записью каталога Vetro. [vetroId] == null: каталог проверили, тайтла там нет. */
data class VetroLink(
    val animeId: String,
    val vetroId: String?,
    val checkedAt: Long,
    val via: String? = null,
)

/** Хранилище связей «тайтл коллекции → Vetro id». Интерфейс нужен, чтобы служба привязки и отсев рекомендаций проверялись без Android. */
interface VetroLinks {
    suspend fun all(): List<VetroLink>
    suspend fun vetroIds(): Set<String>
    suspend fun putAll(links: Collection<VetroLink>)
    suspend fun delete(animeIds: Collection<String>)
}

/** Таблица `vetro_link`: какому тайтлу коллекции какой Vetro id соответствует. */
class VetroLinkStore(private val factory: SQLDelightDatabaseFactory) : VetroLinks {

    private fun queries() = factory.getDatabase().vetroLinkQueries

    override suspend fun all(): List<VetroLink> = withContext(Dispatchers.IO) {
        queries().getAllVetroLinks().executeAsList().map { VetroLink(it.anime_id, it.vetro_id, it.checked_at, it.via) }
    }

    /** Vetro id всех тайтлов коллекции, которые удалось найти в каталоге. */
    override suspend fun vetroIds(): Set<String> = withContext(Dispatchers.IO) {
        queries().getAllVetroLinks().executeAsList().mapNotNullTo(HashSet()) { it.vetro_id }
    }

    override suspend fun putAll(links: Collection<VetroLink>) {
        if (links.isEmpty()) return
        withContext(Dispatchers.IO) {
            val q = queries()
            q.transaction { links.forEach { q.upsertVetroLink(it.animeId, it.vetroId, it.checkedAt, it.via) } }
        }
    }

    override suspend fun delete(animeIds: Collection<String>) {
        if (animeIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val q = queries()
            q.transaction { animeIds.forEach { q.deleteVetroLink(it) } }
        }
    }
}
