package com.abyssredemption.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(UpdateManager.compareVersions("1.0.22", "1.0.9") > 0)
        assertTrue(UpdateManager.compareVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, UpdateManager.compareVersions("1.0.22", "1.0.22"))
        assertEquals(0, UpdateManager.compareVersions("1.0.22", "1.0.22-beta"))
    }

    @Test
    fun parsesGiteeLatestVersionFile() {
        assertEquals("1.0.40", UpdateManager.parseGiteeVersion("Latest version: v1.0.40"))
    }
}
