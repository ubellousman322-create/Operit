package com.ai.assistance.operit.ui.features.settings.screens.theme

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 读取 revokit-theme 格式的主题包：一个 zip，里面有 theme.json 和它引用的图片。
 *
 * 解析出来的图片会被复制进应用私有目录，主题预设里只保存 file:// 路径，
 * 这样删掉原始压缩包也不会影响已经导入的主题。
 */
internal object ThemePackageImporter {

    private const val TAG = "ThemeImport"
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024

    data class ImportedTheme(
        val name: String,
        val values: ThemePreferenceValues,
    )

    suspend fun read(context: Context, uri: Uri): ImportedTheme = withContext(Dispatchers.IO) {
        val workDir =
            File(
                context.cacheDir,
                "theme_import_" + UUID.randomUUID().toString().replace("-", "").take(10),
            )
        val written = mutableListOf<File>()
        try {
            workDir.mkdirs()
            extract(context, uri, workDir)
            val jsonFile =
                locateThemeJson(workDir)
                    ?: throw IllegalArgumentException("压缩包里没有 theme.json")
            val root = JSONObject(jsonFile.readText())
            val baseDir = jsonFile.parentFile ?: workDir
            buildTheme(context, root, baseDir, written)
        } catch (error: Exception) {
            written.forEach { file -> runCatching { file.delete() } }
            throw error
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun extract(context: Context, uri: Uri, targetDir: File) {
        val input =
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法打开所选文件")
        val targetRoot = targetDir.canonicalFile
        val targetPrefix = targetRoot.path + File.separator
        var totalBytes = 0L
        var fileCount = 0
        input.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && isSafeEntryName(name)) {
                        val outFile = File(targetDir, name)
                        if (runCatching { outFile.canonicalFile }.getOrNull()?.path?.startsWith(targetPrefix) == true) {
                            outFile.parentFile?.mkdirs()
                            var entryBytes = 0L
                            outFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    entryBytes += read
                                    totalBytes += read
                                    if (entryBytes > MAX_ENTRY_BYTES) {
                                        throw IllegalArgumentException("包内单个文件过大")
                                    }
                                    if (totalBytes > MAX_TOTAL_BYTES) {
                                        throw IllegalArgumentException("压缩包过大")
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                            fileCount++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (fileCount == 0) {
            throw IllegalArgumentException("压缩包里没有文件")
        }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        return !Regex("^[a-zA-Z]:").containsMatchIn(name)
    }

    private fun locateThemeJson(directory: File): File? {
        val direct = File(directory, "theme.json")
        if (direct.isFile) return direct
        return directory
            .walkTopDown()
            .firstOrNull { file -> file.isFile && file.name.equals("theme.json", ignoreCase = true) }
    }

    private fun buildTheme(
        context: Context,
        root: JSONObject,
        baseDir: File,
        written: MutableList<File>,
    ): ImportedTheme {
        val strings = linkedMapOf<String, String>()
        val booleans = linkedMapOf<String, Boolean>()
        val ints = linkedMapOf<String, Int>()
        val floats = linkedMapOf<String, Float>()

        root.optJSONObject("strings")?.let { block ->
            block.keys().forEach { key -> strings[key] = block.optString(key) }
        }
        root.optJSONObject("booleans")?.let { block ->
            block.keys().forEach { key -> booleans[key] = block.optBoolean(key) }
        }
        root.optJSONObject("ints")?.let { block ->
            block.keys().forEach { key -> ints[key] = block.optInt(key) }
        }
        root.optJSONObject("floats")?.let { block ->
            block.keys().forEach { key -> floats[key] = block.optDouble(key, 0.0).toFloat() }
        }

        if (!ints.containsKey("custom_primary_color") && root.has("primary")) {
            ints["custom_primary_color"] = root.optInt("primary", 0)
        }

        val copiedByDigest = HashMap<String, File>()
        root.optJSONObject("images")?.let { block ->
            block.keys().forEach { key ->
                val relative = block.optString(key).trim()
                if (relative.isEmpty()) return@forEach
                val source = resolveInside(baseDir, relative)
                if (source == null) {
                    AppLogger.d(TAG, "Missing image in package: $relative")
                    return@forEach
                }
                val digest = md5Of(source)
                val internalFile =
                    copiedByDigest.getOrPut(digest) {
                        val extension = source.extension.ifBlank { "png" }
                        val label = key.removeSuffix("_uri").take(24).ifBlank { "asset" }
                        val target =
                            File(
                                context.filesDir,
                                "theme_" + label + "_" +
                                    UUID.randomUUID().toString().replace("-", "").take(10) +
                                    "." + extension,
                            )
                        source.copyTo(target, overwrite = true)
                        written += target
                        target
                    }
                strings[key] = Uri.fromFile(internalFile).toString()
            }
        }

        val name = root.optString("name").trim().ifBlank { "导入的主题" }
        return ImportedTheme(
            name = name,
            values =
                ThemePreferenceValues(
                    strings = strings,
                    booleans = booleans,
                    ints = ints,
                    floats = floats,
                ),
        )
    }

    private fun resolveInside(baseDir: File, relative: String): File? {
        val candidate = File(baseDir, relative)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val basePath = runCatching { baseDir.canonicalFile.path }.getOrNull() ?: return null
        if (!canonical.path.startsWith(basePath + File.separator)) return null
        return canonical.takeIf { it.isFile }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}
