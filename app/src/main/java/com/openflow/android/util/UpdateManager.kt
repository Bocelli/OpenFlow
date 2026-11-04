package com.openflow.android.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String,
    val isNewer: Boolean
)

class UpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = "$GITHUB_VERSION_URL?t=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "OpenFlow-Android-App")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: Impossibile verificare aggiornamenti"))
            }

            val body = response.body?.string() ?: return@withContext Result.failure(IOException("Risposta vuota da GitHub"))
            val json = JSONObject(body)

            val remoteVersionCode = json.optInt("versionCode", 0)
            val remoteVersionName = json.optString("versionName", "1.0.0")
            val releaseNotes = json.optString("releaseNotes", "Nessuna nota di rilascio disponibile.")
            val downloadUrl = json.optString("downloadUrl", DEFAULT_APK_DOWNLOAD_URL)
            val publishedAt = json.optString("publishedAt", "")

            val packageInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) {
                null
            }
            val currentVersionCode: Int = if (packageInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
            } else 2
            val isNewer: Boolean = remoteVersionCode > currentVersionCode

            val info = UpdateInfo(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                publishedAt = publishedAt,
                isNewer = isNewer
            )

            Log.i(TAG, "Verifica aggiornamento completata: Remoto=$remoteVersionName (code=$remoteVersionCode), Locale=$currentVersionCode, isNewer=$isNewer")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Errore verifica aggiornamento: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "OpenFlow-Android-App")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Errore download HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Corpo risposta vuoto"))
            val totalBytes = body.contentLength()

            val apkFile = File(context.cacheDir, "openflow-update.apk")
            if (apkFile.exists()) apkFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var downloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes.toFloat() else 0f
                        withContext(Dispatchers.Main) {
                            onProgress(progress, downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Download APK completato con successo: ${apkFile.length()} bytes")
            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il download dell'APK: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun installApk(activity: Activity, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                return Result.failure(IOException("File APK non trovato o corrotto."))
            }

            // Su Android 8.0+ verifica se l'app è autorizzata a installare pacchetti sconosciuti
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return Result.failure(SecurityException("Autorizzazione per installazione necessaria. Abilita la spunta e riprova."))
                }
            }

            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante avvio installazione APK: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_VERSION_URL =
            "https://raw.githubusercontent.com/Bocelli/OpenFlow/main/version.json"
        private const val DEFAULT_APK_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/Bocelli/OpenFlow/main/releases/OpenFlow-Release-C-NotchDocked.apk"
    }
}
