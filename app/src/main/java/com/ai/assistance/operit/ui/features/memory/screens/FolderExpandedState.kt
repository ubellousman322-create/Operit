package com.ai.assistance.operit.ui.features.memory.screens

import kotlinx.serialization.Serializable

/**
 * 文件夹展开状态的持久化数据类（与 MemoryFolderSelectionDialog 共享）
 */
@Serializable
data class FolderExpandedState(
    val expandedPaths: Set<String> = emptySet()
)
