package com.example.myapplication.support

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * Шина бизнес-событий для очков активности. Источники (добавление тайтла, отметка серии, завершение)
 * сообщают сюда без зависимости от менеджера; синхронизация и восстановление сюда не пишут.
 */
object SupportActivity {
    private val bus = MutableSharedFlow<SupportActivityEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SupportActivityEvent> = bus.asSharedFlow()

    fun report(event: SupportActivityEvent) {
        bus.tryEmit(event)
    }
}

/** Источники времени — подменяются в тестах. */
interface SupportClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long

    /** Номер загрузки устройства (−1, если неизвестен): в пределах одной загрузки часы сверяются с elapsedRealtime. */
    fun bootCount(): Int

    /** Календарные сутки по часам и часовому поясу устройства. */
    fun epochDay(wall: Long): Long
}

class AndroidSupportClock(private val context: Context) : SupportClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = SystemClock.elapsedRealtime()
    override fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    override fun epochDay(wall: Long): Long = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}

/** Состояние напоминаний в своём DataStore (`support_prefs`). Повреждённые данные → состояние «с нуля» (3 дня тишины). */
class SupportReminderStore(private val store: DataStore<Preferences>) {

    suspend fun load(): SupportReminderState = runCatching { decode(store.data.first()) }.getOrDefault(SupportReminderState())

    suspend fun save(s: SupportReminderState) {
        runCatching { store.edit { encode(it, s) } }
    }

    private fun decode(p: Preferences): SupportReminderState {
        val first = p[FIRST_LAUNCH] ?: 0L
        if (first < 0L) return SupportReminderState()
        return SupportReminderState(
            firstLaunchAt = first,
            lastShownAt = p[LAST_SHOWN],
            nextEligibleAt = p[NEXT_ELIGIBLE] ?: 0L,
            activityScore = (p[SCORE] ?: 0).coerceAtLeast(0),
            requiredScore = p[REQUIRED] ?: 0,
            activeSessions = (p[ACTIVE_SESSIONS] ?: 0).coerceAtLeast(0),
            consecutiveDismissals = (p[DISMISSALS] ?: 0).coerceAtLeast(0),
            githubStarActionClicked = p[GITHUB_CLICKED] ?: false,
            donateAvailable = p[DONATE_AVAILABLE] ?: true,
            donationConfirmed = p[DONATION_CONFIRMED] ?: false,
            donationCooldownUntil = p[DONATION_COOLDOWN],
            totalImpressions = p[IMPRESSIONS] ?: 0,
            sessionStartedAt = p[SESSION_STARTED] ?: 0L,
            sessionForegroundMs = (p[SESSION_FG] ?: 0L).coerceAtLeast(0L),
            foregroundSince = p[FOREGROUND_SINCE],
            lastBackgroundAt = p[LAST_BACKGROUND],
            sessionCounted = p[SESSION_COUNTED] ?: false,
            sessionIsFirst = p[SESSION_FIRST] ?: false,
            shownThisSession = p[SHOWN_SESSION] ?: false,
            lastActiveDay = p[LAST_DAY] ?: -1L,
            episodePointsDay = p[EP_DAY] ?: -1L,
            episodePointsToday = p[EP_TODAY] ?: 0,
            seenEvents = p[SEEN]?.split(',')?.mapNotNull { it.toIntOrNull(36) }.orEmpty(),
            clockEffective = p[CLOCK_EFFECTIVE] ?: 0L,
            clockWall = p[CLOCK_WALL] ?: 0L,
            clockElapsed = p[CLOCK_ELAPSED] ?: 0L,
            clockBoot = p[CLOCK_BOOT] ?: -1,
        )
    }

    private fun encode(p: MutablePreferences, s: SupportReminderState) {
        fun <T> put(key: Preferences.Key<T>, value: T?) {
            if (value == null) p.remove(key) else p[key] = value
        }
        put(FIRST_LAUNCH, s.firstLaunchAt)
        put(LAST_SHOWN, s.lastShownAt)
        put(NEXT_ELIGIBLE, s.nextEligibleAt)
        put(SCORE, s.activityScore)
        put(REQUIRED, s.requiredScore)
        put(ACTIVE_SESSIONS, s.activeSessions)
        put(DISMISSALS, s.consecutiveDismissals)
        put(GITHUB_CLICKED, s.githubStarActionClicked)
        put(DONATE_AVAILABLE, s.donateAvailable)
        put(DONATION_CONFIRMED, s.donationConfirmed)
        put(DONATION_COOLDOWN, s.donationCooldownUntil)
        put(IMPRESSIONS, s.totalImpressions)
        put(SESSION_STARTED, s.sessionStartedAt)
        put(SESSION_FG, s.sessionForegroundMs)
        put(FOREGROUND_SINCE, s.foregroundSince)
        put(LAST_BACKGROUND, s.lastBackgroundAt)
        put(SESSION_COUNTED, s.sessionCounted)
        put(SESSION_FIRST, s.sessionIsFirst)
        put(SHOWN_SESSION, s.shownThisSession)
        put(LAST_DAY, s.lastActiveDay)
        put(EP_DAY, s.episodePointsDay)
        put(EP_TODAY, s.episodePointsToday)
        put(SEEN, s.seenEvents.joinToString(",") { it.toString(36) })
        put(CLOCK_EFFECTIVE, s.clockEffective)
        put(CLOCK_WALL, s.clockWall)
        put(CLOCK_ELAPSED, s.clockElapsed)
        put(CLOCK_BOOT, s.clockBoot)
    }

    private companion object {
        val FIRST_LAUNCH = longPreferencesKey("first_launch_at")
        val LAST_SHOWN = longPreferencesKey("last_shown_at")
        val NEXT_ELIGIBLE = longPreferencesKey("next_eligible_at")
        val SCORE = intPreferencesKey("activity_score")
        val REQUIRED = intPreferencesKey("required_score")
        val ACTIVE_SESSIONS = intPreferencesKey("active_sessions")
        val DISMISSALS = intPreferencesKey("consecutive_dismissals")
        val GITHUB_CLICKED = booleanPreferencesKey("github_star_action_clicked")
        val DONATE_AVAILABLE = booleanPreferencesKey("donate_available")
        val DONATION_CONFIRMED = booleanPreferencesKey("donation_confirmed")
        val DONATION_COOLDOWN = longPreferencesKey("donation_cooldown_until")
        val IMPRESSIONS = intPreferencesKey("total_impressions")
        val SESSION_STARTED = longPreferencesKey("session_started_at")
        val SESSION_FG = longPreferencesKey("session_foreground_ms")
        val FOREGROUND_SINCE = longPreferencesKey("foreground_since")
        val LAST_BACKGROUND = longPreferencesKey("last_background_at")
        val SESSION_COUNTED = booleanPreferencesKey("session_counted")
        val SESSION_FIRST = booleanPreferencesKey("session_is_first")
        val SHOWN_SESSION = booleanPreferencesKey("shown_this_session")
        val LAST_DAY = longPreferencesKey("last_active_day")
        val EP_DAY = longPreferencesKey("episode_points_day")
        val EP_TODAY = intPreferencesKey("episode_points_today")
        val SEEN = stringPreferencesKey("seen_events")
        val CLOCK_EFFECTIVE = longPreferencesKey("clock_effective")
        val CLOCK_WALL = longPreferencesKey("clock_wall")
        val CLOCK_ELAPSED = longPreferencesKey("clock_elapsed")
        val CLOCK_BOOT = intPreferencesKey("clock_boot")
    }
}

/**
 * Напоминание о поддержке: учёт сессий (ProcessLifecycleOwner), очки за бизнес-события, решение о показе.
 * Ни опроса, ни таймеров, ни фоновой работы: всё считается на событиях жизненного цикла, бизнес-событиях
 * и при попытке показа, которую делает UI в спокойный момент ([tryShow]). [offer] ≠ null — шторка открыта.
 */
class SupportReminderManager(
    private val store: SupportReminderStore,
    private val scope: CoroutineScope,
    private val clock: SupportClock,
    private val random: Random = Random.Default,
) : DefaultLifecycleObserver {

    private val mutex = Mutex()
    private var state: SupportReminderState? = null
    private var started = false

    private val _offer = MutableStateFlow<List<SupportOption>?>(null)
    val offer: StateFlow<List<SupportOption>?> = _offer.asStateFlow()

    /** Вызывать один раз из Application.onCreate (главный поток). */
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            SupportActivity.events.collect { event ->
                mutate { s, _, day -> SupportReminderPolicy.onEvent(s, event, day) }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch { mutate { s, now, day -> SupportReminderPolicy.onForeground(s, now, day, random) } }
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch { mutate { s, now, _ -> SupportReminderPolicy.onBackground(s, now) } }
    }

    /**
     * Попытка показа в спокойный момент. [safeContext] — проверка UI, сделанная вызывающим прямо перед вызовом.
     * Возвращает true, если шторку надо открыть (варианты — в [offer]).
     */
    suspend fun tryShow(safeContext: Boolean): Boolean {
        if (_offer.value != null || !safeContext) return false
        var options: List<SupportOption>? = null
        mutate { s, now, _ ->
            val counted = SupportReminderPolicy.countSession(s, now)
            if (SupportReminderPolicy.shouldShow(counted, now, safeContext)) {
                options = SupportReminderPolicy.offers(counted)
                SupportReminderPolicy.onShown(counted, now, random)
            } else {
                counted
            }
        }
        options?.let { _offer.value = it }
        return options != null
    }

    /** Закрыли свайпом, кнопкой «назад» или тапом по фону. */
    fun dismiss() = react { s, now -> SupportReminderPolicy.onDismissed(s, now) }

    fun donateClicked() = react { s, now -> SupportReminderPolicy.onDonate(s, now) }

    /** Сначала сохраняем, что GitHub предложен и принят, потом вызывающий открывает ссылку. */
    suspend fun githubClicked() {
        _offer.value = null
        mutate { s, now, _ -> SupportReminderPolicy.onGithub(s, now) }
    }

    /** Для источника, который умеет достоверно подтвердить платёж (сейчас такого нет — переход к донату ≠ оплата). */
    fun donationConfirmed() = react { s, now -> SupportReminderPolicy.onDonationConfirmed(s, now) }

    private fun react(transform: (SupportReminderState, Long) -> SupportReminderState) {
        _offer.value = null
        scope.launch { mutate { s, now, _ -> transform(s, now) } }
    }

    private suspend fun mutate(transform: (SupportReminderState, Long, Long) -> SupportReminderState) {
        mutex.withLock {
            val base = state ?: SupportReminderPolicy.afterProcessStart(store.load())
            val wall = clock.wallMillis()
            val ticked = TrustedClock.advance(base, wall, clock.elapsedMillis(), clock.bootCount())
            val next = transform(ticked, ticked.clockEffective, clock.epochDay(wall))
            state = next
            // Сдвинулись только часы — в DataStore не пишем: их догонит следующая настоящая запись.
            if (next.withoutClock() != base.withoutClock()) store.save(next)
        }
    }

    private fun SupportReminderState.withoutClock() = copy(clockEffective = 0L, clockWall = 0L, clockElapsed = 0L, clockBoot = -1)
}
