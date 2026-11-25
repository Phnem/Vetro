package com.phnem.vetro.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Пакет приложения под тестом. По умолчанию — боевой; тестовые сборки с суффиксом передают свой:
 * `-Pandroid.testInstrumentationRunnerArguments.targetAppId=com.phnem.vetro.perf`.
 */
internal fun targetPackage(): String =
    InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.phnem.vetro"

/**
 * Путь пользователя, по которому собирается профиль и меряются кадры: холодный старт → главная
 * (коллекция) → листание страниц рабочей области туда и обратно → Details первого тайтла и назад.
 * Жесты — в долях экрана: сценарий не зависит от языка интерфейса и содержимого коллекции.
 */
internal fun MacrobenchmarkScope.coldStartToHome() {
    pressHome()
    startActivityAndWait()
    // Главная готова, когда на экране есть нижний док/коллекция. Сплэш — ~3 с фиксированной анимации.
    device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10_000)
    device.waitForIdle()
    Thread.sleep(4_000)
    dismissForeignWindow()
}

/**
 * Окно поверх приложения (запрос разрешения на уведомления) забирает жесты, а следующее «назад»
 * тогда закрывает само приложение. Закрываем его кнопкой «назад», ничего не разрешая и не запрещая.
 */
internal fun MacrobenchmarkScope.dismissForeignWindow() {
    if (device.currentPackageName != packageName) {
        device.pressBack()
        device.waitForIdle()
        Thread.sleep(500)
    }
}

/** Если путь всё же увёл из приложения, возвращаемся: дальше жесты не должны идти по рабочему столу. */
internal fun MacrobenchmarkScope.returnToApp() {
    if (device.currentPackageName != packageName) coldStartToHome()
}

internal fun MacrobenchmarkScope.swipeWorkspacePages() {
    val w = device.displayWidth
    val h = device.displayHeight
    val y = (h * 0.55).toInt()
    repeat(2) {
        device.swipe((w * 0.85).toInt(), y, (w * 0.15).toInt(), y, 12)
        device.waitForIdle()
    }
    repeat(2) {
        device.swipe((w * 0.15).toInt(), y, (w * 0.85).toInt(), y, 12)
        device.waitForIdle()
    }
}

/**
 * Вкладка «Книги»: полки каталога, затем локальная книга в конце списка и плеер. Без локальной
 * аудиокниги на устройстве путь до плеера просто не находит кнопку и пропускается.
 */
internal fun MacrobenchmarkScope.openBooksAndPlay() {
    returnToApp()
    val w = device.displayWidth
    val h = device.displayHeight
    // Третья кнопка нижнего дока.
    device.click((w * 0.48).toInt(), (h * 0.929).toInt())
    device.waitForIdle()
    Thread.sleep(3_000)
    repeat(5) {
        device.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.2).toInt(), 12)
        device.waitForIdle()
    }
    // Локальная полка автора — последняя на вкладке: берём нижнюю подпись «N книг» и жмём под ней
    // по самой полке; за ней лист автора и карточка книги.
    val shelf = device.findObjects(By.text(SHELF_COUNT)).maxByOrNull { it.visibleBounds.top } ?: return
    device.click(w / 2, shelf.visibleBounds.bottom + (h * 0.2).toInt())
    Thread.sleep(1_500)
    device.click((w * 0.26).toInt(), (h * 0.32).toInt())
    val listen = device.wait(Until.findObject(By.text(LISTEN_LABEL)), 5_000)
    if (listen != null) {
        listen.click()
        Thread.sleep(5_000)
        device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
        device.pressBack()
        device.waitForIdle()
    }
}

private val SHELF_COUNT: Pattern = Pattern.compile("\\d+ (books?|книг.*)", Pattern.UNICODE_CASE)

private val LISTEN_LABEL: Pattern =
    Pattern.compile("(Listen|Continue|Слушать|Продолжить).*", Pattern.UNICODE_CASE)

internal fun MacrobenchmarkScope.openFirstTitleAndBack() {
    val w = device.displayWidth
    val h = device.displayHeight
    // Первая карточка коллекции — под баннером рекомендаций.
    device.click((w * 0.2).toInt(), (h * 0.39).toInt())
    device.waitForIdle()
    Thread.sleep(1_500)
    // Прокрутка деталей и назад.
    device.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.3).toInt(), 20)
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
