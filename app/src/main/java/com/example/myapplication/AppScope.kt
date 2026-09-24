package com.example.myapplication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Область корутин уровня процесса — для работы, которая переживает экраны (фоновые проверки,
 * координаторы). Одна на приложение (Koin `single`) вместо самодельной `CoroutineScope(...)` в каждом
 * классе: SupervisorJob — сбой одной задачи не гасит остальные; по умолчанию IO, потому что почти
 * всё, что сюда попадает, — диск и сеть.
 */
class AppScope : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
}
