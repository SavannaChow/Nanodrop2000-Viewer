package com.tbwk.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
)

data class UpdateCheckResult(
    val latestVersion: String,
    val update: AppUpdateInfo?,
)

object UpdateChecker {
    private const val latestReleaseUrl = "https://api.github.com/repos/SavannaChow/Nanodrop2000-Viewer/releases/latest"

    fun checkForUpdate(currentVersion: String): UpdateCheckResult {
        val connection = (URL(latestReleaseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "nanodrop-2000-viewer-android")
        }

        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Bad update response: ${connection.responseCode}")
        }

        connection.inputStream.use { stream ->
            val payload = JSONObject(stream.bufferedReader().readText())
            val latestVersion = normalizeVersion(payload.optString("tag_name"))
            if (!isVersionNewer(latestVersion, normalizeVersion(currentVersion))) {
                return UpdateCheckResult(latestVersion = latestVersion, update = null)
            }

            val assets = payload.optJSONArray("assets") ?: return UpdateCheckResult(latestVersion = latestVersion, update = null)
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name").lowercase()
                if (name.contains("android") && name.endsWith(".apk")) {
                    return UpdateCheckResult(
                        latestVersion = latestVersion,
                        update = AppUpdateInfo(
                            version = latestVersion,
                            notes = payload.optString("body").trim(),
                            downloadUrl = asset.getString("browser_download_url"),
                        )
                    )
                }
            }

            return UpdateCheckResult(latestVersion = latestVersion, update = null)
        }
    }

    private fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    private fun isVersionNewer(latest: String, current: String): Boolean {
        val left = latest.split('.').map { it.toIntOrNull() ?: 0 }
        val right = current.split('.').map { it.toIntOrNull() ?: 0 }
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l > r
        }
        return false
    }
}
