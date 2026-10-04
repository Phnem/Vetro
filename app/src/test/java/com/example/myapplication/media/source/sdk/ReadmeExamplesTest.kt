package com.example.myapplication.media.source.sdk

import com.example.myapplication.media.source.movieseries.custom.CustomSourceInstaller
import com.example.myapplication.media.source.movieseries.custom.PackageParse
import com.example.myapplication.media.source.movieseries.custom.SourceInstallResult
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Примеры из README папки Community sources (копия — docs/community-sources/README.txt, оригинал —
 * на Google Drive) обязаны проходить валидаторы приложения: по ним сообщество пишет свои файлы.
 * Поменялся формат — обновить README в обоих местах и эти файлы.
 */
class ReadmeExamplesTest {
    private val installer = CustomSourceInstaller()

    private fun example(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("sdk/readme/$name.json")).readText()

    @Test
    fun `full package example installs`() {
        val result = installer.fromPackageJson(example("full_package"))
        assertTrue(result.toString(), result is PackageParse.Ready)
    }

    @Test
    fun `minimal package example installs`() {
        val result = installer.fromPackageJson(example("min_package"))
        assertTrue(result.toString(), result is PackageParse.Ready)
    }

    @Test
    fun `the import button lets every example through, with or without a BOM`() {
        listOf("full_package", "min_package", "manifest_v1").forEach { name ->
            assertTrue(name, installer.looksLikeJsonObject(example(name)))
            assertTrue("$name with BOM", installer.looksLikeJsonObject("\uFEFF" + example(name)).not())
            assertTrue("$name BOM stripped", installer.looksLikeJsonObject(("\uFEFF" + example(name)).removePrefix("\uFEFF")))
        }
        assertTrue(installer.looksLikeJsonObject("https://addon.example.com/manifest.json").not())
    }

    @Test
    fun `manifest v1 example installs`() {
        val result = installer.fromManifestJson(example("manifest_v1"))
        assertTrue(result.toString(), result is SourceInstallResult.Installed)
    }
}
