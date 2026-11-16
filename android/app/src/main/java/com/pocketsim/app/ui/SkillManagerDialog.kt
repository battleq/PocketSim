/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsim.app.AiSkill
import com.pocketsim.app.R

/**
 * 自定义 AI 技能管理面板：已有技能（编辑/删除）+ 新建/编辑表单。
 * 只有用户自定义技能会出现在列表里，内置 3 条只读。
 */
@Composable
fun SkillManagerDialog(
    customSkills: List<AiSkill>,
    onDismiss: () -> Unit,
    onSave: (AiSkill) -> Unit,
    onDelete: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skill_dialog_title)) },
        text = {
            SkillManagerContent(
                customSkills = customSkills,
                onSave = onSave,
                onDelete = onDelete
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_close)) }
        }
    )
}

/** 面板正文（独立组合函数：Robolectric 截图测试可直接渲染，不必依赖对话框窗口） */
@Composable
internal fun SkillManagerContent(
    customSkills: List<AiSkill>,
    onSave: (AiSkill) -> Unit,
    onDelete: (String) -> Unit
) {
    var editingId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val requiredMsg = stringResource(R.string.skill_required)

    fun resetForm() {
        editingId = ""
        name = ""
        brief = ""
        instruction = ""
        error = null
    }

    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.skill_manager_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (customSkills.isEmpty()) {
            Text(
                text = stringResource(R.string.skill_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            customSkills.forEach { skill ->
                Card(
                    shape = RoundedCornerShape(4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (skill.brief.isNotBlank()) {
                                Text(
                                    text = skill.brief,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                editingId = skill.id
                                name = skill.name
                                brief = skill.brief
                                instruction = skill.instruction
                                error = null
                            }
                        ) { Text(stringResource(R.string.skill_edit)) }
                        TextButton(
                            onClick = {
                                onDelete(skill.id)
                                if (editingId == skill.id) resetForm()
                            }
                        ) { Text(stringResource(R.string.skill_delete)) }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        Text(
            text = if (editingId.isBlank()) {
                stringResource(R.string.skill_new)
            } else {
                stringResource(R.string.skill_editing, name)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.skill_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = brief,
            onValueChange = { brief = it },
            label = { Text(stringResource(R.string.skill_brief_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            label = { Text(stringResource(R.string.skill_instruction_label)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (editingId.isNotBlank()) {
                TextButton(onClick = { resetForm() }) {
                    Text(stringResource(R.string.skill_cancel_edit))
                }
            }
            Button(
                onClick = {
                    val draft = AiSkill.normalize(editingId, name, brief, instruction)
                    if (draft == null) {
                        error = requiredMsg
                    } else {
                        onSave(draft)
                        resetForm()
                    }
                }
            ) { Text(stringResource(R.string.skill_save)) }
        }
    }
}
