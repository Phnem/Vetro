package com.phnem.vetro.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

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
