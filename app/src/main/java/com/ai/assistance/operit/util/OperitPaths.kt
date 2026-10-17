package com.ai.assistance.operit.util

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import android.os.Environment
import java.io.File

object OperitPaths {

    private const val OPERIT_DIR_NAME = "Operit"
    private const val AVE_OPERIT_DIR_NAME = "Operit-ave"
    private const val CLEAN_ON_EXIT_DIR_NAME = "cleanOnExit"
    private const val PLUGINS_DIR_NAME = "plugins"
    private const val MCP_PLUGINS_DIR_NAME = "mcp_plugins"
    private const val BRIDGE_DIR_NAME = "bridge"
    private const val EXPORTS_DIR_NAME = "exports"
    private const val WORKSPACE_DIR_NAME = "workspace"
    private const val TEST_DIR_NAME = "test"
    private const val WEBSESSION_DIR_NAME = "websession"
    private const val USERSCRIPTS_DIR_NAME = "userscripts"

    const val SHERPA_NCNN_MODELS_DIR_NAME = ".sherpa_ncnn_models"
    const val VECTOR_INDEX_DIR_NAME = ".vector_index"

    const val IMAGE_POOL_DIR_NAME = "image_pool"
    const val MEDIA_POOL_DIR_NAME = "media_pool"
    const val SKILL_REPO_ZIP_POOL_DIR_NAME = "skill_repo_zip_pool"

    fun downloadsDir(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    fun operitRootDir(): File {
        val root = File(downloadsDir(), operitDirName())
        if (!isOfficialPackage()) {
            ensureAveMigrated(root)
        }
        return ensureDir(root)
    }

    /** 当前构建使用的 Operit 数据目录名：官方包沿用 "Operit"，自构建版本（ave 等）使用独立目录。 */
    fun operitDirName(): String {
        return if (isOfficialPackage()) OPERIT_DIR_NAME else AVE_OPERIT_DIR_NAME
    }

    private fun isOfficialPackage(): Boolean {
        return BuildConfig.APPLICATION_ID == "com.ai.assistance.operit"
    }

    /** R23-D: 首次使用独立目录时，从旧的公共目录复制一份运行时数据（只补缺，不覆盖）。 */
    @Synchronized
    private fun ensureAveMigrated(aveRoot: File) {
        try {
            val marker = File(aveRoot, ".migrated_from_legacy_v2")
            if (marker.exists()) return
            val legacyRoot = File(downloadsDir(), OPERIT_DIR_NAME)
            if (legacyRoot.exists() && legacyRoot.isDirectory) {
                listOf(
                    "plugins",
                    "mcp_plugins",
                    "skills",
                    "workflow",
                    "bridge",
                    "workspace",
                    "websession",
                    "pluginConfigs",
                    "output images"
                ).forEach { name ->
                    try {
                        copyDirIfMissing(File(legacyRoot, name), File(aveRoot, name))
                    } catch (ignored: Exception) {
                    }
                }
            }
            // models 通常很大：优先整体移动（同盘 rename 近乎瞬时），失败则跳过，避免复制大文件
            try {
                val legacyModels = File(legacyRoot, "models")
                val aveModels = File(aveRoot, "models")
                if (legacyModels.exists() && !aveModels.exists()) {
                    legacyModels.renameTo(aveModels)
                }
            } catch (ignored: Exception) {
            }
            // 外部使用验证文件（免费额度计数）
            try {
                val legacyUsage = File(legacyRoot, "usage_verification.dat")
                val aveUsage = File(aveRoot, "usage_verification.dat")
                if (legacyUsage.exists() && !aveUsage.exists()) {
                    legacyUsage.copyTo(aveUsage, overwrite = false)
                }
            } catch (ignored: Exception) {
            }
            ensureDir(aveRoot)
            try {
                marker.writeText("migrated")
            } catch (ignored: Exception) {
            }
        } catch (ignored: Exception) {
        }
    }

    private fun copyDirIfMissing(src: File, dst: File) {
        if (!src.exists() || !src.isDirectory) return
        if (!dst.exists()) dst.mkdirs()
        val children = src.listFiles() ?: return
        for (child in children) {
            val target = File(dst, child.name)
            if (target.exists()) continue
            if (child.isDirectory) {
                copyDirIfMissing(child, target)
            } else {
                try {
                    child.copyTo(target, overwrite = false)
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun cleanOnExitDir(): File {
        return ensureDir(File(operitRootDir(), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun pluginsDir(): File {
        return ensureDir(File(operitRootDir(), PLUGINS_DIR_NAME))
    }

    fun pluginConfigDir(pluginId: String): File {
        val trimmed = pluginId.trim()
        val safeBaseName =
            trimmed
                .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                .trim('.', ' ')
                .ifBlank { "plugin" }
        val safeName =
            if (safeBaseName == trimmed) {
                safeBaseName
            } else {
                "$safeBaseName-${Integer.toHexString(trimmed.hashCode())}"
            }
        return ensureDir(File(pluginsDir(), safeName))
    }

    fun cleanOnExitInternalDir(context: Context): File {
        return ensureDir(File(ensureDir(File(context.cacheDir, OPERIT_DIR_NAME)), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun mcpPluginsDir(): File {
        return ensureDir(File(operitRootDir(), MCP_PLUGINS_DIR_NAME))
    }

    fun bridgeDir(): File {
        return ensureDir(File(operitRootDir(), BRIDGE_DIR_NAME))
    }

    fun exportsDir(): File {
        return ensureDir(File(operitRootDir(), EXPORTS_DIR_NAME))
    }

    fun workspaceDir(): File {
        return ensureDir(File(operitRootDir(), WORKSPACE_DIR_NAME))
    }

    fun testDir(): File {
        return ensureDir(File(operitRootDir(), TEST_DIR_NAME))
    }

    fun webSessionDir(): File {
        return ensureDir(File(operitRootDir(), WEBSESSION_DIR_NAME))
    }

    fun webSessionUserscriptsDir(): File {
        return ensureDir(File(webSessionDir(), USERSCRIPTS_DIR_NAME))
    }

    fun sherpaNcnnModelsDir(context: Context): File {
        return ensureDir(File(context.filesDir, SHERPA_NCNN_MODELS_DIR_NAME))
    }

    fun vectorIndexDir(context: Context): File {
        return ensureDir(File(context.filesDir, VECTOR_INDEX_DIR_NAME))
    }

    fun imagePoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, IMAGE_POOL_DIR_NAME))
    }

    fun mediaPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, MEDIA_POOL_DIR_NAME))
    }

    fun skillRepoZipPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, SKILL_REPO_ZIP_POOL_DIR_NAME))
    }

    fun rawSnapshotExcludedFilesTopLevelDirNames(): Set<String> {
        return setOf(
            SHERPA_NCNN_MODELS_DIR_NAME,
            VECTOR_INDEX_DIR_NAME,
            IMAGE_POOL_DIR_NAME,
            MEDIA_POOL_DIR_NAME,
            SKILL_REPO_ZIP_POOL_DIR_NAME,
        )
    }

    fun operitRootPathSdcard(): String {
        return "/sdcard/Download/${operitDirName()}"
    }

    fun cleanOnExitPathSdcard(): String {
        return "${operitRootPathSdcard()}/$CLEAN_ON_EXIT_DIR_NAME"
    }

    fun pluginsPathSdcard(): String {
        return "${operitRootPathSdcard()}/$PLUGINS_DIR_NAME"
    }

    fun bridgePathSdcard(): String {
        return "${operitRootPathSdcard()}/$BRIDGE_DIR_NAME"
    }

    fun exportsPathSdcard(): String {
        return "${operitRootPathSdcard()}/$EXPORTS_DIR_NAME"
    }

    fun workspacePathSdcard(chatId: String): String {
        return "${operitRootPathSdcard()}/$WORKSPACE_DIR_NAME/$chatId"
    }

    fun testPathSdcard(): String {
        return "${operitRootPathSdcard()}/$TEST_DIR_NAME"
    }

    fun webSessionUserscriptsPathSdcard(): String {
        return "${operitRootPathSdcard()}/$WEBSESSION_DIR_NAME/$USERSCRIPTS_DIR_NAME"
    }

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
