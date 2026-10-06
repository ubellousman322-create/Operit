package com.ai.assistance.operit.ui.features.settings.screens.theme

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues
import com.ai.assistance.operit.data.preferences.ThemePresetSummary
import kotlinx.coroutines.launch

@Composable
internal fun ThemeSettingsPresetBar(
    presetCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() },
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.theme_preset_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.theme_preset_subtitle, presetCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.theme_preset_manage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun ThemePresetManagerDialog(
    activePromptManager: ActivePromptManager,
    target: ActivePrompt,
    currentValues: ThemePreferenceValues,
    onPresetApplied: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf(emptyList<ThemePresetSummary>()) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ThemePresetSummary?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        presets = activePromptManager.listThemePresets()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.theme_preset_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                if (presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.theme_preset_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    presets.forEach { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = preset.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            activePromptManager.applyThemePresetToTarget(preset.id, target)
                                            Toast.makeText(
                                                    context,
                                                    context.getString(R.string.theme_preset_applied, preset.name),
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                            onPresetApplied()
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                    context,
                                                    context.getString(R.string.theme_preset_apply_failed),
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.theme_preset_apply))
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = { renameTarget = preset },
                            ) {
                                Text(stringResource(R.string.theme_preset_rename))
                            }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            activePromptManager.deleteThemePreset(preset.id)
                                            presets = activePromptManager.listThemePresets()
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                    context,
                                                    context.getString(R.string.theme_preset_delete_failed),
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.theme_preset_delete))
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.theme_preset_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        val name = newName.trim()
                        busy = true
                        scope.launch {
                            try {
                                activePromptManager.saveThemePreset(name, currentValues)
                                newName = ""
                                presets = activePromptManager.listThemePresets()
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.theme_preset_saved, name),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.theme_preset_save_failed),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.theme_preset_save_current))
                }

                Text(
                    text = stringResource(R.string.theme_preset_apply_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.theme_preset_close))
            }
        },
    )

    val editing = renameTarget
    if (editing != null) {
        ThemePresetRenameDialog(
            initialName = editing.name,
            onConfirm = { name ->
                renameTarget = null
                busy = true
                scope.launch {
                    try {
                        activePromptManager.renameThemePreset(editing.id, name)
                        presets = activePromptManager.listThemePresets()
                    } catch (e: Exception) {
                        Toast.makeText(
                                context,
                                context.getString(R.string.theme_preset_rename_failed),
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun ThemePresetRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_preset_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.theme_preset_rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.theme_preset_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_action))
            }
        },
    )
}
