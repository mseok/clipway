package dev.mseok.clipway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val hash = "ab".repeat(32)

    private fun manifest(version: String = "0.2.0", file: String = "Clipway-android.apk", size: Long = 5678) =
        """{"version":"$version","mac":{"file":"Clipway-mac.zip","sha256":"$hash","size":1234},""" +
            """"android":{"file":"$file","sha256":"$hash","size":$size}}"""

    @Test
    fun readsTheAndroidPartOfAManifest() {
        assertEquals(Release("0.2.0", "Clipway-android.apk", hash, 5678), Release.parse(manifest()))
    }

    @Test
    fun rejectsMalformedManifests() {
        val bad = listOf(
            "",
            "not json",
            """{"version":"0.2.0"}""",
            manifest(version = "latest"),
            manifest(file = "../Clipway-android.apk"),
            manifest(file = ".hidden"),
            manifest(size = 0),
            manifest(size = Release.MAX_APK_BYTES + 1),
            manifest().replace(hash, "xyz"),
        )
        for (json in bad) assertNull(json, Release.parse(json))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Release.isNewer("0.10.0", "0.9.9"))
        assertTrue(Release.isNewer("0.1.1", "0.1"))
        assertFalse(Release.isNewer("0.1.0", "0.1.0"))
        assertFalse(Release.isNewer("1.0", "1.0.0"))
        assertFalse(Release.isNewer("0.1.0", "0.2.0"))
        for (bad in listOf("", "1..2", "v1.0", "1.0-beta", "1.2.3.4.5", "12345678.0")) {
            assertFalse(bad, Release.isNewer(bad, "0.0.1"))
        }
    }
}
