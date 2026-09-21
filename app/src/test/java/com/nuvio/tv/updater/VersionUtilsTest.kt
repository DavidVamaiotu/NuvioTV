package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {
    @Test
    fun `stable release is newer than its prerelease`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-rc.2"))
    }

    @Test
    fun `beta identifiers use numeric ordering`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.10", "1.1.0-beta.9"))
    }

    @Test
    fun `prerelease for a later minor is newer than current stable`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.1", "1.0.3"))
    }

    @Test
    fun `stable channel patch is not newer than later minor beta`() {
        assertFalse(VersionUtils.isRemoteNewer("1.0.4", "1.1.0-beta.2"))
    }

    @Test
    fun `version prefix and build metadata do not affect precedence`() {
        assertFalse(VersionUtils.isRemoteNewer("v1.0.0+18", "1.0.0+17"))
    }

    @Test
    fun `invalid remote version is not offered`() {
        assertFalse(VersionUtils.isRemoteNewer("latest", "1.0.0"))
    }

    @Test
    fun `current beta naming is recognized as prerelease`() {
        assertTrue(VersionUtils.isPrerelease("0.8.12-beta"))
    }

    @Test
    fun `autosync revision is newer than exact upstream version`() {
        assertTrue(VersionUtils.isRemoteNewer("1.0.0-autosync.1", "1.0.0"))
    }

    @Test
    fun `autosync revisions increment independently`() {
        assertTrue(VersionUtils.isRemoteNewer("1.0.0-autosync.10", "1.0.0-autosync.9"))
        assertFalse(VersionUtils.isRemoteNewer("1.0.0-autosync.2", "1.0.0-autosync.2"))
    }

    @Test
    fun `new upstream version outranks previous autosync revision`() {
        assertTrue(VersionUtils.isRemoteNewer("1.0.1-autosync.1", "1.0.0-autosync.99"))
    }

    @Test
    fun `stable autosync revision is not treated as prerelease`() {
        assertFalse(VersionUtils.isPrerelease("1.0.0-autosync.2"))
        assertTrue(VersionUtils.isPrerelease("1.0.1-beta-autosync.2"))
    }

    @Test
    fun `canonical autosync tags require numeric revision`() {
        assertTrue(VersionUtils.isCanonicalAutoSync("1.0.0-autosync.1"))
        assertTrue(VersionUtils.isCanonicalAutoSync("0.9.5-beta-autosync.12"))
        assertFalse(VersionUtils.isCanonicalAutoSync("1.0.1-autosync"))
        assertFalse(VersionUtils.isCanonicalAutoSync("1.0.1"))
    }
}
