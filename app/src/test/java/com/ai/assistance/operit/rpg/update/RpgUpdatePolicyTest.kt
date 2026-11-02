package com.ai.assistance.operit.rpg.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RpgUpdatePolicyTest {
    private val appId = "io.github.cgm2026.operitrpg"
    private fun manifest() = JSONObject().put("applicationId", appId).put("versionName", "0.1.1")
        .put("versionCode", 2).put("apkUrl", "https://github.com/CGM2026/OperitRPG/releases/download/rpg-v0.1.1/operit-rpg-0.1.1.apk")
        .put("sizeBytes", 100L).put("sha256", "a".repeat(64))
    private fun parse(data: JSONObject, installed: Long = 1) = RpgUpdatePolicy.release(data, "rpg-v0.1.1", "说明", appId, installed)
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected rejected update") } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsNewReleaseAndIgnoresCurrentOrOlderVersions() {
        assertEquals("0.1.1", parse(manifest())!!.version)
        assertNull(parse(manifest(), 2))
        assertNull(parse(manifest(), 3))
    }

    @Test fun dedicatedVersionKeepsTheLegacyPackageAndReleaseAssetContract() {
        val data = manifest().put("versionName", "0.2.0").put("versionCode", 2)
            .put("apkUrl", "https://github.com/CGM2026/OperitRPG/releases/download/rpg-v0.2.0/operit-rpg-0.2.0.apk")
        val update = RpgUpdatePolicy.release(data, "rpg-v0.2.0", "二十面骰", appId, 1)
        assertEquals(2L, update!!.code)
        assertEquals("0.2.0", update.version)
        assertNull(RpgUpdatePolicy.release(data, "rpg-v0.2.0", "二十面骰", appId, 2))
    }

    @Test fun refusesWrongPackageVersionOrForeignRepository() {
        rejects { parse(manifest().put("applicationId", "com.ai.assistance.operit")) }
        rejects { parse(manifest().put("versionName", "0.2.0")) }
        rejects { parse(manifest().put("apkUrl", "https://github.com/attacker/app/releases/download/rpg-v0.1.1/operit-rpg-0.1.1.apk")) }
    }

    @Test fun refusesOversizedEmptyAndUnverifiableDownloads() {
        rejects { parse(manifest().put("sizeBytes", RpgUpdatePolicy.MAX_APK_BYTES + 1)) }
        rejects { parse(manifest().put("sizeBytes", 0)) }
        rejects { parse(manifest().put("sha256", "bad")) }
    }

    @Test fun permitsOnlyHttpsGithubAssetsWithoutCredentialsOrCustomPorts() {
        RpgUpdatePolicy.validateUrl("https://release-assets.githubusercontent.com/asset")
        rejects { RpgUpdatePolicy.validateUrl("http://github.com/asset") }
        rejects { RpgUpdatePolicy.validateUrl("https://github.com.attacker.example/asset") }
        rejects { RpgUpdatePolicy.validateUrl("https://github.com@attacker.example/asset") }
        rejects { RpgUpdatePolicy.validateUrl("https://user@github.com/asset") }
        rejects { RpgUpdatePolicy.validateUrl("https://github.com:8443/asset") }
    }

    @Test fun revalidatesEveryRedirectAndResolvesRelativeTargets() {
        assertEquals("https://github.com/next", RpgUpdatePolicy.redirect("https://github.com/old", "/next"))
        assertEquals("https://release-assets.githubusercontent.com/file", RpgUpdatePolicy.redirect("https://github.com/old", "https://release-assets.githubusercontent.com/file"))
        rejects { RpgUpdatePolicy.redirect("https://github.com/old", "//attacker.example/file") }
        rejects { RpgUpdatePolicy.redirect("https://github.com/old", "http://github.com/file") }
        rejects { RpgUpdatePolicy.redirect("https://github.com/old", null) }
    }
}
