/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/** 顶部扩展：项目 DataStore 实例（App 私有，单例） */
private val Context.projectsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketsim_projects")

/**
 * 已保存的仿真项目（模型 + 结果一起持久化，打开即可回放结果页）。
 * 框架模式：modelJson / resultJson 为 FrameModel / FrameResult 的 JSON；
 * 场景模板模式：modelJson 为空，scenarioResultJson 存 FeaResult JSON。
 */
@Serializable
data class SavedProject(
    val id: Long = 0L,
    val name: String = "",
    /** "frame" 框架模式 / "scenario" 场景模板模式 */
    val kind: String = "frame",
    val modelJson: String = "",
    val resultJson: String = "",
    /** 安全结论摘要，供列表展示 */
    val verdict: String = "",
    val maxStressMPa: Double = 0.0,
    val safetyFactor: Double = 0.0,
    val createdAt: Long = 0L
)

/** 项目仓储：DataStore 保存已完成的仿真项目，供"打开老项目"回放 */
class ProjectRepository(private val context: Context) {

    private object Keys {
        val PROJECTS_JSON = stringPreferencesKey("projects_json")
    }

    /** 全部项目（按创建时间倒序） */
    val projects: Flow<List<SavedProject>> = context.projectsDataStore.data.map { p ->
        val raw = p[Keys.PROJECTS_JSON] ?: "[]"
        try {
            AppJson.decodeFromString(ListSerializer(SavedProject.serializer()), raw)
                .sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun current(): List<SavedProject> = projects.first()

    /** 保存一个项目（同名覆盖；否则追加） */
    suspend fun save(project: SavedProject): SavedProject {
        val list = current().toMutableList()
        val existing = list.indexOfFirst { it.id == project.id }
        if (existing >= 0) {
            list[existing] = project
        } else {
            list.add(project)
        }
        context.projectsDataStore.edit {
            it[Keys.PROJECTS_JSON] = AppJson.encodeToString(ListSerializer(SavedProject.serializer()), list)
        }
        return project
    }

    suspend fun delete(id: Long) {
        val list = current().filter { it.id != id }
        context.projectsDataStore.edit {
            it[Keys.PROJECTS_JSON] = AppJson.encodeToString(ListSerializer(SavedProject.serializer()), list)
        }
    }
}
