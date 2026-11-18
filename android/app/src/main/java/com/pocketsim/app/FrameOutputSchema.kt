/*
 * Copyright (c) 2024-2026 PocketSim 口袋仿真. All rights reserved.
 * SPDX-License-Identifier: LicenseRef-PocketSim-Proprietary
 * 本文件为 PocketSim 专有代码，未经书面许可不得复制、修改或分发；商业授权见 /LICENSE-COMMERCIAL
 */

package com.pocketsim.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 框架建模通道的输出契约 JSON Schema（配合 response_format=json_schema + strict:true 使用）。
 *
 * 设计取舍：
 *  - 顶层 [AiFrameResult] 信封严格约束（additionalProperties=false），杜绝模型自由发挥字段；
 *  - `model`（FrameModel）用 anyOf[null, object] 强类型化：坐标/节点/杆件/支座/载荷的字段名与
 *    类型被 token 级约束解码锁定，从源头消除「合法 JSON 但坐标/字段错」导致的重试（建模慢主因）；
 *  - 其余 kind（flow/seepage/transient/solid/solid_generic/shell）的模型对象用
 *    additionalProperties=true 宽松放过：不破坏其他 6 类建模与 autoDim 切 kind，同时这些通道
 *    后续仍由 [AiParser] 的语义校验兜底。
 *  - 仅顶层 required=["ready"]：其余字段缺省由 [AiFrameResult] 默认值兜底，避免 strict 强制
 *    补齐所有字段引发 finish_reason=length 截断。
 *
 * 兼容：不支持 json_schema 的服务（4xx）由 [LlmClientImpl] 自动降级回 json_object。
 */
internal val FRAME_OUTPUT_SCHEMA: JsonObject = Json.parseToJsonElement(
    """
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "ready": {"type": "boolean", "description": "信息是否足以建立完整模型；out_of_scope 时恒为 false"},
    "kind": {"type": "string", "description": "模型类别：frame/flow/seepage/transient/solid/solid_generic/shell_vessel/shell_tank/shell_buried_pipe/out_of_scope，缺省 frame"},
    "category": {"type": "string", "description": "仅 kind=out_of_scope 时必填：简短任务类别名（如「电磁-结构耦合」），用于拼接待客话术；其余 kind 留空"},
    "questions": {"type": "array", "items": {"type": "string"}, "description": "信息不足时的追问，每轮最多 2 条；out_of_scope 时为空数组"},
    "answer": {"type": "string", "description": "助理说明；建模模式一般留空"},
    "observation": {"type": "string", "description": "带图建模的几何观测；不带图时留空"},
    "model": {
      "anyOf": [
        {"type": "null"},
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "name": {"type": "string"},
            "dim": {"type": "integer", "description": "2 平面 / 3 空间，缺省 2"},
            "nodes": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "id": {"type": "integer"},
                  "x": {"type": "number", "description": "mm，2D 水平向右"},
                  "y": {"type": "number", "description": "mm，2D 竖直向上"},
                  "z": {"type": "number", "description": "mm，3D 竖直向上；2D 恒为 0"}
                }
              }
            },
            "members": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "id": {"type": "integer"},
                  "i": {"type": "integer", "description": "端节点 id，nodes 中必须存在"},
                  "j": {"type": "integer", "description": "端节点 id，nodes 中必须存在"},
                  "width_b": {"type": "number", "description": "截面面外宽度 mm"},
                  "depth_h": {"type": "number", "description": "截面面内高度 mm"},
                  "material": {"type": "string", "description": "材料中文名，查材料库"},
                  "wall_t": {"type": "number", "description": "空心截面壁厚 mm，0 为实心"},
                  "shape": {"type": "string", "description": "rect 矩形（默认）/ circle 圆管"},
                  "outer_diameter_mm": {"type": "number", "description": "圆管外径 mm，shape=circle 时用"},
                  "holes": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "at_mm": {"type": "number"},
                        "shape": {"type": "string"},
                        "d_mm": {"type": "number"},
                        "through": {"type": "boolean"}
                      }
                    }
                  }
                }
              }
            },
            "supports": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "node": {"type": "integer"},
                  "type": {"type": "string", "description": "fixed/pinned/roller；3D 另支持 roller_x/roller_y"},
                  "fix": {"type": "array", "items": {"type": "string"}, "description": "额外约束自由度，3D 用如 [rx]"}
                }
              }
            },
            "loads": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "type": {"type": "string", "description": "force 节点集中力 / udl 杆件均布 / area 面荷载（按杆件长度自动均分） / wind 风载（源字段，代码换算） / thermal 温度变化"},
                  "node": {"type": ["integer", "null"], "description": "force 的作用节点 id"},
                  "member": {"type": ["integer", "null"], "description": "udl/thermal 的作用杆件 id"},
                  "members": {"type": ["array", "null"], "items": {"type": "integer"}, "description": "area 承托面荷载的杆件 id 列表"},
                  "total_N": {"type": "number", "description": "area 作用总力（N，向下为负），按杆件长度分配为均布载荷"},
                  "nodes": {"type": ["array", "null"], "items": {"type": "integer"}, "description": "wind 迎风节点 id 列表，净风力均摊到这些节点"},
                  "wind_speed_m_s": {"type": ["number", "null"], "description": "wind 风速 m/s，与 beaufort 二选一"},
                  "beaufort": {"type": ["integer", "null"], "description": "wind 蒲福风级 0~17，与 wind_speed_m_s 二选一"},
                  "dir": {"type": "string", "description": "wind 受力方向 x/y（3D 可 z），默认 x"},
                  "area_m2": {"type": "number", "description": "wind 受风投影面积 m²，>0"},
                  "cp": {"type": "number", "description": "wind 净体型系数，缺省 1.2"},
                  "fx": {"type": "number"},
                  "fy": {"type": "number"},
                  "fz": {"type": "number"},
                  "mx": {"type": "number"},
                  "my": {"type": "number"},
                  "mz": {"type": "number"},
                  "qx": {"type": "number"},
                  "qy": {"type": "number"},
                  "qz": {"type": "number"},
                  "delta_temp": {"type": "number", "description": "温度变化 °C，升温正降温负"}
                }
              }
            }
          }
        }
      ]
    },
    "flow": {"type": "object", "additionalProperties": true},
    "seepage": {"type": "object", "additionalProperties": true},
    "transient": {"type": "object", "additionalProperties": true},
    "solid": {"type": "object", "additionalProperties": true},
    "solid_generic": {"type": "object", "additionalProperties": true},
    "shell": {"type": "object", "additionalProperties": true,
      "description": "shell_vessel / shell_tank 薄壁容器模型对象（几何 geometry + 材料 material + 填料 fill）；可选 thermal 字段：{\"inner_temp_c\":浸湿面温度(°C),\"outer_temp_c\":外露面温度(°C),\"ref_temp_c\":无应力参考温度(°C,缺省20)}，用于稳态壁厚温差热应力，省略时不计温度应力"},
    "shell_buried_pipe": {"type": "object", "additionalProperties": true,
      "description": "shell_buried_pipe 埋地管模型对象：geometry{outer_diameter_m,thickness_m,cover_depth_m 必填；可选 soil_gamma_kN_m3/soil_phi_deg/soil_cohesion_kPa/wheel_load_kN/tire_contact_m/internal_pressure_kPa} + material（材料库名，可选 E_MPa/nu/density_kg_m3/strength_MPa 覆盖）"}
  },
  "required": ["ready"]
}
    """.trimIndent()
).jsonObject
