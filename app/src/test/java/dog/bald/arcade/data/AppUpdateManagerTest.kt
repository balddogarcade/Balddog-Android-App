package dog.bald.arcade.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `isNewerVersion detects major minor and patch increments`() {
        assertTrue(AppUpdateManager.isNewerVersion("0.2.0", "0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.0", "0.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("0.1.1", "0.1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v0.1.2", "0.1.1"))
        assertTrue(AppUpdateManager.isNewerVersion("0.2.0-rc.1", "0.1.9"))
    }

    @Test
    fun `isNewerVersion returns false for equal or older versions`() {
        assertFalse(AppUpdateManager.isNewerVersion("0.1.0", "0.1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.1.0", "0.1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("0.0.9", "0.1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("0.1.0", "0.2.0"))
        assertFalse(AppUpdateManager.isNewerVersion("invalid", "0.1.0"))
    }

    @Test
    fun `parseSha256Content extracts 64-char hex digest`() {
        val digest = "a".repeat(64)
        assertEquals(digest, AppUpdateManager.parseSha256Content("$digest  app-release.apk\n"))
        assertEquals(digest, AppUpdateManager.parseSha256Content("  ${digest.uppercase()}  "))
        assertNull(AppUpdateManager.parseSha256Content("not-a-valid-sha256"))
        assertNull(AppUpdateManager.parseSha256Content(""))
    }

    @Test
    fun `verifyApkSha256 validates matching file and rejects tampered file`() {
        val file = tempFolder.newFile("app-release.apk")
        file.writeBytes("balddog-arcade-apk-payload".toByteArray())

        val actualSha = AppUpdateManager.computeFileSha256(file)
        assertTrue(AppUpdateManager.verifyApkSha256(file, actualSha))
        assertTrue(AppUpdateManager.verifyApkSha256(file, actualSha.uppercase()))

        val wrongSha = "0".repeat(64)
        assertFalse(AppUpdateManager.verifyApkSha256(file, wrongSha))
        assertFalse(AppUpdateManager.verifyApkSha256(file, "short"))
    }
}
