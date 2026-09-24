package com.example.myapplication.ui.shared

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Связь между доком рабочей области и строкой поиска на странице коллекции.
 *
 * Строка поиска принадлежит `HomeScreen`, а док живёт СНАРУЖИ пейджера, в `WorkspaceScreen`.
 * Прямого вызова между ними нет и быть не должно: страница — сменный элемент, док — постоянный.
 *
 * Общение идёт заявкой, а не вызовом: док только просит открыть или закрыть поиск, страница
 * заявку выполняет, когда окажется на экране. Это не перестраховка — пейджер держит в композиции
 * лишь соседние страницы, и коллекция может быть ещё не собрана в тот момент, когда по кнопке
 * поиска уже нажали с дальнего раздела. Прямой колбэк в этот момент просто пропал бы.
 */
@Stable
class WorkspaceSearchState {

    /** Открыт ли сейчас поиск. Пишет страница коллекции, читает док — для подсветки кнопки. */
    var active by mutableStateOf(false)
        internal set

    /** Невыполненная заявка: `true` — открыть, `false` — закрыть, `null` — заявок нет. */
    internal var pending by mutableStateOf<Boolean?>(null)
        private set

    /** Нажали кнопку поиска: закрываем открытый поиск, иначе просим открыть. */
    fun toggle() {
        pending = !active
    }

    /** Страница забрала заявку. */
    internal fun consume(): Boolean? = pending.also { pending = null }

    /** Страница коллекции сообщает своё состояние поиска. */
    internal fun report(isActive: Boolean) {
        active = isActive
    }
}

val LocalWorkspaceSearch = compositionLocalOf { WorkspaceSearchState() }
