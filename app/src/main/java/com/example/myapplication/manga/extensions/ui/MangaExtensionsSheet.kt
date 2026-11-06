package com.example.myapplication.manga.extensions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.myapplication.manga.extensions.CatalogExtension
import com.example.myapplication.manga.extensions.LoadedExtension
import com.example.myapplication.manga.extensions.MangaExtensionManager
import com.example.myapplication.ui.shared.components.GrabberReservedTop
import com.example.myapplication.ui.shared.components.IosSegmentedControl
import com.example.myapplication.ui.shared.fluidClickable
import com.example.myapplication.ui.shared.theme.BrandOrange
import com.example.myapplication.ui.shared.theme.IosDesign
import com.example.myapplication.ui.shared.theme.IosScroll
import com.example.myapplication.ui.shared.theme.SnProFamily
import com.example.myapplication.ui.shared.theme.SquircleShape
import com.example.myapplication.ui.shared.theme.isAppInDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Шторка «Расширения Mihon»: каталог Keiyoushi прямо в Vetro вместо ссылки на GitHub.
 *
 * Главное действие — одна кнопка «Подключить рекомендованные»: крупные каталоги языка приложения
 * ставятся по очереди с прогрессом. Ниже — подключённые (обновить/удалить) и весь каталог с
 * поиском. Установка идёт в [scope] страницы, а не шторки: закрытая шторка не обрывает загрузку.
 *
 * @param onChanged набор источников изменился — после закрытия шторки выбор источника ищет заново.
 */
@Composable
fun MangaExtensionsSheet(
    ru: Boolean,
    scope: CoroutineScope,
    onChanged: () -> Unit,
    manager: MangaExtensionManager = koinInject(),
) {
    val isDark = isAppInDarkTheme()
    val installed by manager.installed.collectAsStateWithLifecycle()
    var catalog by remember { mutableStateOf<List<CatalogExtension>?>(null) }
    var catalogFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var recommended by remember { mutableStateOf<List<CatalogExtension>>(emptyList()) }
    var bulk by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val working = remember { mutableStateMapOf<String, Boolean>() }
    val failures = remember { mutableStateMapOf<String, String>() }
    var query by remember { mutableStateOf("") }
    var allLanguages by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey) {
        catalogFailed = false
        manager.ensureLoaded()
        runCatching { manager.catalog(forceRefresh = reloadKey > 0) }
            .onSuccess { catalog = it }
            .onFailure { catalogFailed = true }
    }
    LaunchedEffect(catalog, installed) {
        recommended = catalog?.let { manager.recommendedToInstall(it) }.orEmpty()
    }

    fun install(extension: CatalogExtension) {
        if (working[extension.packageName] == true) return
        working[extension.packageName] = true
        failures.remove(extension.packageName)
        scope.launch {
            manager.install(extension)
                .onSuccess { onChanged() }
                .onFailure { failures[extension.packageName] = it.message ?: it.javaClass.simpleName }
            working.remove(extension.packageName)
        }
    }

    fun installRecommended() {
        val list = recommended
        if (list.isEmpty() || bulk != null) return
        scope.launch {
            bulk = 0 to list.size
            list.forEachIndexed { index, extension ->
                working[extension.packageName] = true
                manager.install(extension)
                    .onSuccess { failures.remove(extension.packageName) }
                    .onFailure { failures[extension.packageName] = it.message ?: it.javaClass.simpleName }
                working.remove(extension.packageName)
                bulk = index + 1 to list.size
            }
            bulk = null
            onChanged()
        }
    }

    fun uninstall(packageName: String) {
        scope.launch {
            manager.uninstall(packageName)
            onChanged()
        }
    }

    val myLanguage = if (ru) "ru" else "en"
    val needle = query.trim().lowercase()
    val available = catalog.orEmpty().filter { extension ->
        extension.packageName !in installed &&
            (allLanguages || myLanguage in extension.languages || "all" in extension.languages) &&
            (needle.isEmpty() || extension.name.lowercase().contains(needle))
    }
    val catalogByPackage = catalog.orEmpty().associateBy { it.packageName }
    val installedList = installed.values
        .filter { needle.isEmpty() || it.info.name.lowercase().contains(needle) }
        .sortedBy { it.info.name.lowercase() }

    LazyColumn(
        flingBehavior = IosScroll.flingBehavior(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = GrabberReservedTop + 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = if (ru) "Расширения Mihon" else "Mihon extensions",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = SnProFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (ru) {
                        "Источники манги из каталога Keiyoushi. Ставятся внутрь Vetro — в систему ничего не устанавливается, подпись каждого расширения проверяется."
                    } else {
                        "Manga sources from the Keiyoushi catalog. They live inside Vetro — nothing is installed system-wide, and every extension's signature is checked."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }

        item(key = "primary") {
            PrimaryAction(
                ru = ru,
                catalogReady = catalog != null,
                catalogFailed = catalogFailed,
                recommended = recommended,
                bulk = bulk,
                onInstall = ::installRecommended,
                onRetry = { reloadKey++ },
            )
        }

        item(key = "filters") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(ru = ru, value = query, isDark = isDark, onValueChange = { query = it })
                IosSegmentedControl(
                    optionCount = 2,
                    selectedIndex = if (allLanguages) 1 else 0,
                    isDark = isDark,
                    onSelect = { allLanguages = it == 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) { index, _ ->
                    Text(
                        text = when (index) {
                            0 -> if (ru) "Русские" else "English"
                            else -> if (ru) "Все языки" else "All languages"
                        },
                        fontFamily = SnProFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (installedList.isNotEmpty()) {
            item(key = "installed_header") {
                SectionHeader(if (ru) "Подключены · ${installedList.size}" else "Installed · ${installedList.size}")
            }
            item(key = "installed_group") {
                Group(isDark) {
                    installedList.forEachIndexed { index, loaded ->
                        if (index > 0) Separator(isDark)
                        val update = catalogByPackage[loaded.info.packageName]
                            ?.takeIf { it.versionCode > loaded.info.versionCode }
                        InstalledRow(
                            ru = ru,
                            loaded = loaded,
                            busy = working[loaded.info.packageName] == true,
                            failure = failures[loaded.info.packageName],
                            update = update,
                            onUpdate = { update?.let(::install) },
                            onRemove = { uninstall(loaded.info.packageName) },
                        )
                    }
                }
            }
        }

        if (catalog != null) {
            item(key = "catalog_header") {
                SectionHeader(if (ru) "Каталог · ${available.size}" else "Catalog · ${available.size}")
            }
            if (available.isEmpty()) {
                item(key = "catalog_empty") {
                    Text(
                        text = if (ru) "Ничего не нашлось" else "Nothing found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SnProFamily,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                // Отдельными элементами списка, а не одной группой: в «Все языки» их сотни.
                items(available, key = { "c_${it.packageName}" }) { extension ->
                    Box(
                        Modifier
                            .clip(SquircleShape(14.dp))
                            .background(IosDesign.groupBackground(isDark)),
                    ) {
                        CatalogRow(
                            ru = ru,
                            extension = extension,
                            busy = working[extension.packageName] == true,
                            failure = failures[extension.packageName],
                            onInstall = { install(extension) },
                        )
                    }
                }
            }
        }

        item(key = "footer") {
            Text(
                text = if (ru) {
                    "Расширения — сторонние сайты. Vetro не проверяет их содержимое и права на публикацию; страницы открываются так же, как в браузере."
                } else {
                    "Extensions are third-party sites. Vetro doesn't vet their content or publishing rights; pages load just like in a browser."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = SnProFamily,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    ru: Boolean,
    catalogReady: Boolean,
    catalogFailed: Boolean,
    recommended: List<CatalogExtension>,
    bulk: Pair<Int, Int>?,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val loading = (!catalogReady && !catalogFailed) || bulk != null
        val done = catalogReady && recommended.isEmpty() && bulk == null
        val label = when {
            bulk != null -> if (ru) "Подключаю ${bulk.first} из ${bulk.second}…" else "Installing ${bulk.first} of ${bulk.second}…"
            catalogFailed && !catalogReady -> if (ru) "Каталог недоступен · повторить" else "Catalog unavailable · retry"
            !catalogReady -> if (ru) "Загружаю каталог…" else "Loading catalog…"
            done -> if (ru) "Рекомендованные подключены" else "Recommended are installed"
            else -> if (ru) "Подключить рекомендованные · ${recommended.size}" else "Install recommended · ${recommended.size}"
        }
        val action: (() -> Unit)? = when {
            loading || done -> null
            catalogFailed && !catalogReady -> onRetry
            else -> onInstall
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CircleShape)
                .background(if (done) BrandOrange.copy(alpha = 0.14f) else BrandOrange)
                .fluidClickable(enabled = action != null, onClick = { action?.invoke() }),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(18.dp))
                loading -> CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                else -> Icon(Icons.Rounded.Extension, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = if (done) BrandOrange else Color.White,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
        if (bulk != null) {
            LinearProgressIndicator(
                progress = { if (bulk.second == 0) 0f else bulk.first / bulk.second.toFloat() },
                color = BrandOrange,
                trackColor = BrandOrange.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(CircleShape),
            )
        } else if (recommended.isNotEmpty()) {
            val names = recommended.map { it.name }
            val shown = names.take(3).joinToString(", ")
            Text(
                text = if (names.size > 3) {
                    if (ru) "$shown и ещё ${names.size - 3}" else "$shown and ${names.size - 3} more"
                } else {
                    shown
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun SearchField(ru: Boolean, value: String, isDark: Boolean, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(SquircleShape(12.dp))
            .background(IosDesign.groupBackground(isDark))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = if (ru) "Найти расширение" else "Find an extension",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = SnProFamily,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(BrandOrange),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = SnProFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun Group(isDark: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(SquircleShape(16.dp))
            .background(IosDesign.groupBackground(isDark)),
    ) { content() }
}

@Composable
private fun Separator(isDark: Boolean) {
    Box(
        Modifier
            .padding(start = 64.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(IosDesign.separator(isDark)),
    )
}

@Composable
private fun InstalledRow(
    ru: Boolean,
    loaded: LoadedExtension,
    busy: Boolean,
    failure: String?,
    update: CatalogExtension?,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val info = loaded.info
    val subtitle = when {
        failure != null -> (if (ru) "Не обновилось: " else "Update failed: ") + failure
        loaded.error != null -> (if (ru) "Не загрузилось: " else "Failed to load: ") + loaded.error
        else -> languagesLabel(info.languages) + " · v" + info.versionName + " · " + sourcesLabel(ru, loaded.sources.size)
    }
    ExtensionRow(
        name = info.name,
        iconUrl = info.iconUrl,
        subtitle = subtitle,
        subtitleIsError = failure != null || loaded.error != null,
    ) {
        when {
            busy -> SmallSpinner()
            update != null -> Pill(if (ru) "Обновить" else "Update", accent = true, onClick = onUpdate)
            else -> Pill(if (ru) "Удалить" else "Remove", accent = false, onClick = onRemove)
        }
    }
}

@Composable
private fun CatalogRow(
    ru: Boolean,
    extension: CatalogExtension,
    busy: Boolean,
    failure: String?,
    onInstall: () -> Unit,
) {
    ExtensionRow(
        name = extension.name,
        iconUrl = extension.iconUrl,
        subtitle = failure?.let { (if (ru) "Не подключилось: " else "Install failed: ") + it }
            ?: (languagesLabel(extension.languages) + " · v" + extension.versionName),
        subtitleIsError = failure != null,
    ) {
        if (busy) SmallSpinner() else Pill(if (ru) "Добавить" else "Add", accent = true, onClick = onInstall)
    }
}

@Composable
private fun ExtensionRow(
    name: String,
    iconUrl: String?,
    subtitle: String,
    subtitleIsError: Boolean,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(SquircleShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = SnProFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = if (subtitleIsError) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = SnProFamily,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
private fun Pill(text: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (accent) BrandOrange.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .fluidClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            color = if (accent) BrandOrange else MaterialTheme.colorScheme.onSurface,
            fontFamily = SnProFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SmallSpinner() {
    CircularProgressIndicator(
        color = BrandOrange,
        strokeWidth = 2.dp,
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .size(18.dp),
    )
}

private fun languagesLabel(languages: List<String>): String = when {
    languages.isEmpty() -> "—"
    languages.size > 3 -> languages.take(3).joinToString(" ") { it.uppercase() } + " +${languages.size - 3}"
    else -> languages.joinToString(" ") { it.uppercase() }
}

private fun sourcesLabel(ru: Boolean, count: Int): String = when {
    !ru -> if (count == 1) "1 source" else "$count sources"
    count % 10 == 1 && count % 100 != 11 -> "$count источник"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count источника"
    else -> "$count источников"
}
