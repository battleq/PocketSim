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
import kotlinx.serialization.builtins.ListSerializer

/** 顶部扩展：AI 技能 DataStore 实例（App 私有，单例） */
private val Context.skillsDataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketsim_skills")

/**
 * AI 技能仓储：内置技能直接取 [AiSkill.BUILT_INS]，
 * 自定义技能以 JSON 列表持久化（增删改）。
 */
class SkillRepository(private val context: Context) {

    private object Keys {
        val CUSTOM_JSON = stringPreferencesKey("custom_skills_json")
    }

    /** 用户自定义技能（按保存顺序） */
    val custom: Flow<List<AiSkill>> = context.skillsDataStore.data.map { p ->
        val raw = p[Keys.CUSTOM_JSON] ?: "[]"
        try {
            AppJson.decodeFromString(ListSerializer(AiSkill.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun currentCustom(): List<AiSkill> = custom.first()

    /**
     * 保存自定义技能（id 已存在则覆盖，空 id 生成新 id）。
     * 名称/指令为空、或占用内置 id 时返回 null，不写入。
     */
    suspend fun save(skill: AiSkill): AiSkill? {
        val saved = AiSkill.normalize(skill.id, skill.name, skill.brief, skill.instruction)
            ?: return null
        val list = currentCustom().toMutableList()
        val index = list.indexOfFirst { it.id == saved.id }
        if (index >= 0) list[index] = saved else list.add(saved)
        write(list)
        return saved
    }

    suspend fun delete(id: String) {
        write(currentCustom().filterNot { it.id == id })
    }

    private suspend fun write(list: List<AiSkill>) {
        context.skillsDataStore.edit {
            it[Keys.CUSTOM_JSON] =
                AppJson.encodeToString(ListSerializer(AiSkill.serializer()), list)
        }
    }
}
