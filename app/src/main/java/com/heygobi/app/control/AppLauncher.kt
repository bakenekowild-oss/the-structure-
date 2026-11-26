package com.heygobi.app.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * "Open <app>" by voice.
 *
 * Matches the spoken name against every launchable app on the phone with
 * a small scoring function (exact > starts-with > contains > word overlap
 * > close spelling). No QUERY_ALL_PACKAGES permission is used: the app list
 * comes from the launcher intent query declared in the manifest.
 */
class AppLauncher(private val context: Context) {

    data class AppInfo(val label: String, val packageName: String)

    fun listLaunchableApps(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
            AppInfo(label, info.activityInfo.packageName)
        }.sortedBy { it.label.lowercase() }
    }

    /** Best match for a spoken name, or null when nothing is close enough. */
    fun findApp(spoken: String): AppInfo? {
        val name = spoken.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (name.isEmpty()) return null
        val apps = listLaunchableApps()
        var best: AppInfo? = null
        var bestScore = 0
        for (app in apps) {
            val score = score(name, app.label.lowercase())
            if (score > bestScore) {
                bestScore = score
                best = app
            }
        }
        return if (bestScore >= 50) best else null
    }

    fun launch(app: AppInfo): Boolean {
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(app.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?: return false
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun score(spoken: String, label: String): Int {
        if (spoken == label) return 100
        if (label.startsWith(spoken)) return 85
        if (label.contains(spoken)) return 70
        val spokenWords = spoken.split(" ").filter { it.length > 1 }
        val labelWords = label.split(" ")
        val overlap = spokenWords.count { sw -> labelWords.any { lw -> lw.startsWith(sw) || sw.startsWith(lw) } }
        if (overlap > 0 && overlap == spokenWords.size) return 60
        if (levenshtein(spoken, label) <= 2 && spoken.length >= 4) return 55
        return 0
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
        }
        return dp[a.length][b.length]
    }
}
