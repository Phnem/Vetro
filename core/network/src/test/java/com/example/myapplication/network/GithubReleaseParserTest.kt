package com.example.myapplication.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReleaseParserTest {

    private val sha = "a".repeat(64)

    private fun release(tag: String, draft: Boolean = false, assets: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/o/r/releases/tag/$tag","draft":$draft,"body":"notes of $tag","assets":[$assets]}"""

    private fun asset(name: String, size: Long, digest: String? = null): String {
        val digestPart = if (digest != null) ""","digest":"$digest"""" else ""
        return """{"name":"$name","size":$size,"browser_download_url":"https://github.com/o/r/releases/download/x/$name"$digestPart}"""
    }

    @Test
    fun the_apk_is_taken_even_when_it_is_not_the_first_asset() {
        val body = "[" + release("v3.3.8-Beta", assets = asset("SHA256SUMS.txt", 120) + "," + asset("app-release.apk", 33_799_985L, "sha256:$sha")) + "]"
        val info = GithubReleaseParser.parse(body)!!
        assertEquals("v3.3.8-Beta", info.tagName)
        assertEquals("app-release.apk", info.apkName)
        assertEquals(33_799_985L, info.apkAsset?.size)
        assertEquals("https://github.com/o/r/releases/download/x/app-release.apk", info.downloadUrl)
        assertEquals(sha, info.sha256)
        assertEquals("notes of v3.3.8-Beta", info.body)
    }

    @Test
    fun a_release_without_an_apk_has_no_download() {
        val info = GithubReleaseParser.parse("[" + release("v1", assets = asset("notes.txt", 5)) + "]")!!
        assertNull(info.apkAsset)
        assertNull(info.apkName)
        assertEquals("", info.downloadUrl)
    }

    @Test
    fun drafts_are_skipped_and_the_newest_published_release_wins() {
        val body = "[" + release("v9-draft", draft = true, assets = asset("a.apk", 1)) + "," +
            release("v3.3.7-Stable", assets = asset("app-release.apk", 10)) + "," +
            release("v3.3.6-Stable", assets = asset("app-release.apk", 9)) + "]"
        assertEquals("v3.3.7-Stable", GithubReleaseParser.parse(body)!!.tagName)
    }

    @Test
    fun only_a_sha256_digest_is_accepted() {
        assertEquals(sha, GithubReleaseParser.sha256Of("sha256:${sha.uppercase()}"))
        assertNull(GithubReleaseParser.sha256Of("sha1:$sha"))
        assertNull(GithubReleaseParser.sha256Of("sha256:short"))
        assertNull(GithubReleaseParser.sha256Of("sha256:" + "z".repeat(64)))
        assertNull(GithubReleaseParser.sha256Of(""))
        val noDigest = GithubReleaseParser.parse("[" + release("v1", assets = asset("app.apk", 1)) + "]")!!
        assertNull(noDigest.sha256)
    }

    @Test
    fun empty_or_foreign_answers_give_no_release() {
        assertNull(GithubReleaseParser.parse("[]"))
        assertNull(GithubReleaseParser.parse("""{"message":"Not Found"}"""))
        assertNull(GithubReleaseParser.parse("""[{"tag_name":""}]"""))
    }
}
