package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReshapedApkAssetsTest {
    private val tag = "1.1.0-beta.1-autosync.11"
    private val releaseAssets = listOf(
        "NuvioTV-AutoSync-$tag-bridge-universal.apk",
        "NuvioRS-TV-$tag-arm64.apk",
        "NuvioRS-TV-$tag-arm32.apk",
        "NuvioRS-TV-$tag-x64.apk",
        "NuvioRS-TV-$tag-x32.apk",
        "NuvioRS-TV-$tag-any.apk",
    )
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    @Test
    fun `picks the Nuvio RS APK for the device ABI`() {
        assertEquals("NuvioRS-TV-$tag-arm64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("NuvioRS-TV-$tag-arm32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("armeabi-v7a")))
        assertEquals("NuvioRS-TV-$tag-x64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86_64")))
        assertEquals("NuvioRS-TV-$tag-x32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86")))
        assertEquals("NuvioRS-TV-$tag-any.apk", ReshapedApkAssets.choose(releaseAssets, listOf("riscv64")))
    }

    @Test
    fun `ignores releases from before the rename`() {
        assertNull(ReshapedApkAssets.choose(listOf("NuvioTV-AutoSync-1.0.0-autosync.9-arm64-v8a.apk"), abis))
    }

    /** The asset selection of builds released before the rename (AbiSelector), which must land on the bridge. */
    @Test
    fun `pre-rename updaters pick the bridge`() {
        abis.forEach { abi ->
            assertEquals("NuvioTV-AutoSync-$tag-bridge-universal.apk", preRenameChoice(releaseAssets, listOf(abi)))
        }
    }

    private fun preRenameChoice(names: List<String>, supported: List<String>): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apks.size == 1) return apks.first()
        for (abi in supported) {
            apks.firstOrNull { it.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { val n = it.lowercase(); n.contains("universal") || n.contains("all") }
            ?: apks.firstOrNull { name -> abis.none { name.contains(it, ignoreCase = true) } }
            ?: apks.first()
    }
}
