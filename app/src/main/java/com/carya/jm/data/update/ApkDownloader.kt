package com.carya.jm.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 更新 APK 下载器。
 *
 * 文件管理约定（全部集中在 `cache/update/` 专用目录，启动时整体清理）：
 * - 下载中：`update_<version>.apk.download`（临时文件）
 * - 下载完成并校验通过后重命名为：`update_<version>.apk`
 * - 任何失败（网络 / 大小 / 校验）都会删除临时文件与不完整的目标文件
 *
 * 这样即使 App 在下载过程中被杀死，也不会留下一个"看起来完整"的半截 APK。
 */
class ApkDownloader(context: Context) {

    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 更新文件专用目录：`cacheDir/update/`。 */
    fun updateDir(): File = File(appContext.cacheDir, "update").apply { mkdirs() }

    /**
     * 下载指定版本的 APK，返回校验通过的最终文件。
     *
     * @param onProgress 进度回调（已下载字节，总字节；总字节未知为 -1），在 IO 线程回调
     * @throws IOException 下载失败 / 文件不完整 / 校验失败（内部已清理临时文件）
     */
    suspend fun download(
        url: String,
        version: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = updateDir()
        val finalFile = File(dir, "update_$version.apk")
        val tmpFile = File(dir, "update_$version.apk.download")

        try {
            val request = Request.Builder().url(url).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载失败（HTTP ${response.code}）")
                }
                val body = response.body ?: throw IOException("下载响应为空")
                val total = body.contentLength() // 可能为 -1（服务器未给出）

                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPct = -1

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            // 百分比变化时才回调，避免高频刷新 UI 状态
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(downloaded, total)
                                }
                            }
                        }
                        output.flush()
                        // 落盘，防止"缓冲写入未刷到磁盘"的假完整文件
                        try {
                            output.fd.sync()
                        } catch (_: Exception) {
                        }
                    }
                }

                // ---- 完整性校验 ----
                if (tmpFile.length() <= 0) {
                    throw IOException("下载文件为空")
                }
                // 服务器给出总大小时，必须精确匹配
                if (total > 0 && tmpFile.length() != total) {
                    throw IOException("下载不完整")
                }
                // APK 即 zip，校验文件头魔数，防止拿到的不是安装包
                validateZipMagic(tmpFile)
            }

            // 校验通过：重命名为最终 APK 文件
            if (finalFile.exists()) {
                finalFile.delete()
            }
            if (!tmpFile.renameTo(finalFile)) {
                throw IOException("更新文件保存失败")
            }
            finalFile
        } catch (e: Exception) {
            // 失败：删除不完整文件（临时 + 目标），恢复可重试状态
            tmpFile.delete()
            finalFile.delete()
            throw e
        }
    }

    /** 校验文件是否为 zip/APK 格式（魔数 PK\u0003\u0004 等）。 */
    private fun validateZipMagic(file: File) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) throw IOException("下载文件异常")
            val magic = raf.readInt()
            val isZip = magic == 0x504B0304 ||   // 本地文件头
                magic == 0x504B0102 ||           // 中央目录
                magic == 0x504B0506              // 中央目录结束
            if (!isZip) {
                throw IOException("安装包校验失败")
            }
        }
    }
}
