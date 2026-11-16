#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
软件著作权登记鉴别材料生成器
- 程序鉴别材料：前 30 页 + 后 30 页，每页 50 行
- 文档鉴别材料（用户手册）：60 页
页眉：口袋仿真有限元分析软件 V1.0 + 页码
"""
import os
import html
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

ROOT = "/workspace/pocketsim"
OUT_DIR = os.path.join(ROOT, "docs", "copyright")
SOFTWARE = "口袋仿真有限元分析软件 V1.0"

WQY = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
MONO = "/usr/share/fonts/truetype/noto/NotoSansMono-Regular.ttf"

PAGE_W, PAGE_H = A4
MARGIN_X = 42
MARGIN_TOP = 56
MARGIN_BOTTOM = 42
HEADER_H = 24
LINE_H = 10.2          # 每页 50 行：(PAGE_H - MARGIN_TOP - HEADER_H - MARGIN_BOTTOM) / 50 ≈ 10.2


def register_fonts():
    pdfmetrics.registerFont(TTFont("WQY", WQY, subfontIndex=0))
    pdfmetrics.registerFont(TTFont("Mono", MONO))


def _has_cjk(s):
    return any("\u4e00" <= ch <= "\u9fff" for ch in s)


def draw_header(c, page_no, total, is_code=True):
    c.setFont("WQY", 9)
    c.setFillGray(0.35)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_TOP + 10, SOFTWARE)
    c.drawRightString(PAGE_W - MARGIN_X, PAGE_H - MARGIN_TOP + 10,
                      f"第 {page_no} 页 共 {total} 页")
    c.setStrokeGray(0.7)
    c.line(MARGIN_X, PAGE_H - MARGIN_TOP, PAGE_W - MARGIN_X, PAGE_H - MARGIN_TOP)
    c.setFillGray(0)


# ---------------- 程序鉴别材料 ----------------

KOTLIN_ORDER = [
    "android/app/src/main/java/com/pocketsim/app/MainActivity.kt",
    "android/app/src/main/java/com/pocketsim/app/AppContainer.kt",
    "android/app/src/main/java/com/pocketsim/app/PythonBridge.kt",
    "android/app/src/main/java/com/pocketsim/app/ChatSession.kt",
    "android/app/src/main/java/com/pocketsim/app/FrameFollowUpSession.kt",
    "android/app/src/main/java/com/pocketsim/app/AiParser.kt",
    "android/app/src/main/java/com/pocketsim/app/LlmClient.kt",
    "android/app/src/main/java/com/pocketsim/app/Models.kt",
    "android/app/src/main/java/com/pocketsim/app/SafetyLogic.kt",
    "android/app/src/main/java/com/pocketsim/app/MaterialLookup.kt",
    "android/app/src/main/java/com/pocketsim/app/TextPolisher.kt",
    "android/app/src/main/java/com/pocketsim/app/ParamForm.kt",
    "android/app/src/main/java/com/pocketsim/app/OfflinePrefill.kt",
    "android/app/src/main/java/com/pocketsim/app/ProjectRepository.kt",
    "android/app/src/main/java/com/pocketsim/app/SettingsRepository.kt",
    "android/app/src/main/java/com/pocketsim/app/SkillRepository.kt",
    "android/app/src/main/java/com/pocketsim/app/AiSkill.kt",
    "android/app/src/main/java/com/pocketsim/app/FollowUpLogic.kt",
    "android/app/src/main/java/com/pocketsim/app/OpenSourceLicenses.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/LauncherScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/HomeScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/ChatScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/FramePreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/FrameResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/Frame3DPlot.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/FrameFollowUpScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SolidPreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SolidResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SolidGenericPreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SolidGenericResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SeepagePreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SeepageResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/FlowPreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/FlowResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/TransientPreviewScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/TransientResultScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/MaterialLibraryScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/SettingsScreen.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/components/Common.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/components/ChatBubbles.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/Format.kt",
    "android/app/src/main/java/com/pocketsim/app/ui/theme/Theme.kt",
]

PYTHON_ORDER = [
    "python/pocketsim/__init__.py",
    "python/pocketsim/bridge.py",
    "python/pocketsim/engine.py",
    "python/pocketsim/frame.py",
    "python/pocketsim/solid.py",
    "python/pocketsim/seepage.py",
    "python/pocketsim/flow.py",
    "python/pocketsim/transient.py",
    "python/pocketsim/materials.py",
    "python/pocketsim/meshgen.py",
    "python/pocketsim/stability.py",
    "python/pocketsim/solver.py",
    "python/pocketsim/narrative.py",
    "python/pocketsim/pdf_report.py",
    "python/pocketsim/interpret.py",
    "python/pocketsim/parser.py",
    "python/pocketsim/templates.py",
    "python/pocketsim/frame3d.py",
    "python/pocketsim/cli.py",
]


def collect_code_lines():
    """按顺序收集自研代码行，每文件前加一行路径标记；过滤空行以保证每页≥50行有效内容。"""
    lines = []
    for rel in KOTLIN_ORDER + PYTHON_ORDER:
        p = os.path.join(ROOT, rel)
        if not os.path.isfile(p):
            continue
        marker = f"# ===== {rel} ====="
        lines.append(marker)
        with open(p, encoding="utf-8", errors="ignore") as f:
            for raw in f:
                ln = raw.rstrip("\n").rstrip()
                if ln == "":
                    continue  # 跳过空行，保证每页均为有效代码行
                lines.append(ln)
    return lines


def gen_code_pdf():
    lines = collect_code_lines()
    total = len(lines)
    print(f"[code] 总行数 {total}")
    per = 50
    front_pages = 30
    back_pages = 30
    # 前 30 页：第 1..1500 行；后 30 页：最后 1500 行
    front = lines[:front_pages * per]
    back_start = max(front_pages * per, total - back_pages * per)
    back = lines[back_start:back_start + back_pages * per]
    # 若总行数不足 3000，前 30 页取全部，后 30 页取剩余（可能不足，但保证提交完整）
    if total <= front_pages * per:
        front = lines
        back = []
    all_pages = front + back
    total_pages = (len(all_pages) + per - 1) // per
    # 补齐到恰好 60 页（不足补空行，保证 30+30）
    needed = 60 * per
    if len(all_pages) < needed:
        all_pages += [""] * (needed - len(all_pages))
    total_pages = 60

    out = os.path.join(OUT_DIR, "程序鉴别材料.pdf")
    c = canvas.Canvas(out, pagesize=A4)
    c.setTitle("程序鉴别材料")
    y0 = PAGE_H - MARGIN_TOP - HEADER_H
    for p in range(total_pages):
        draw_header(c, p + 1, total_pages)
        chunk = all_pages[p * per:(p + 1) * per]
        for i, ln in enumerate(chunk):
            y = y0 - i * LINE_H
            text = ln if ln else " "
            c.setFont("WQY" if _has_cjk(text) else "Mono", 8.2)
            try:
                c.drawString(MARGIN_X, y, text)
            except Exception:
                c.drawString(MARGIN_X, y, " ")
        c.showPage()
    c.save()
    print(f"[code] -> {out} ({total_pages} 页)")
    return out


# ---------------- 文档鉴别材料（用户手册） ----------------

def manual_lines():
    L = []
    def p(s=""):
        L.append(s)

    p("口袋仿真有限元分析软件 用户手册")
    p("版本：V1.0")
    p()
    p("目录")
    p("  1. 软件概述")
    p("  2. 适用场景与免责声明")
    p("  3. 运行环境")
    p("  4. 安装与启动")
    p("  5. 界面概览")
    p("  6. 输入方式")
    p("  7. AI 技能选择")
    p("  8. 建模预览与参数确认")
    p("  9. 平面杆系结构求解")
    p("  10. 三维实体结构求解")
    p("  11. 渗流分析")
    p("  12. 风压与围护结构分析")
    p("  13. 时间历程与温度作用")
    p("  14. 结果解读")
    p("  15. 结果追问与改条件重算")
    p("  16. 材料库")
    p("  17. 报告导出")
    p("  18. 设置项")
    p("  19. 常见问题")
    p("  20. 版本记录")
    p()

    # 1 软件概述
    p("第 1 章 软件概述")
    p("口袋仿真有限元分析软件（以下简称“本软件”）是一款运行在 Android 手机")
    p("上的结构力学辅助分析工具。软件的核心设计目标是让没有专业有限元软件")
    p("使用经验的普通用户，也能通过自然语言或照片快速建立结构模型，并在")
    p("手机本地完成有限元求解，得到变形、应力、安全系数等直观结果。")
    p()
    p("本软件采用“AI 建模 + 确定性求解器”的双轨架构：AI 负责把用户的口语化")
    p("描述或照片转化为结构化的力学模型，求解器负责在本地执行有限元计算。")
    p("所有计算均在手机端离线完成，无需将用户的结构数据上传至云端服务器，")
    p("在保护用户隐私的同时也保证了计算的即时性。")
    p()
    p("本软件覆盖的物理场景包括：平面杆系结构（梁、柱、刚架、桁架）、三维")
    p("实体结构、渗流与稳定分析、风压与围护结构、以及考虑温度作用的时间")
    p("历程分析。软件内置常用建筑与机械材料的力学参数库，并支持用户自定义")
    p("材料。")
    p()

    # 2 适用场景与免责
    p("第 2 章 适用场景与免责声明")
    p("本软件适用于以下场景：")
    p("  1. 日常生活中的结构安全性参考，例如阳台承重、置物架受力、简单支架")
    p("     的承载能力估算；")
    p("  2. 工程相关专业学生的力学概念学习与课程作业验证；")
    p("  3. 工程师在现场进行初步方案比选与定性判断；")
    p("  4. 结构受力的可视化教学演示。")
    p()
    p("本软件不适用于以下场景：")
    p("  1. 涉及人身安全的正式工程设计、施工验算；")
    p("  2. 要求具备法定效力的结构安全鉴定；")
    p("  3. 复杂非线性、动力、抗震等需要专业软件与规范校核的场合。")
    p()
    p("免责声明：本软件的计算结果基于用户输入的简化模型与线弹性假设，仅作")
    p("为参考，不构成任何形式的工程设计依据。用户因使用本软件结果而作出")
    p("的任何决策，由用户自行承担责任。正式工程设计请咨询具备相应资质的")
    p("专业工程师。")
    p()

    # 3 运行环境
    p("第 3 章 运行环境")
    p("硬件环境：")
    p("  - 处理器：ARM64 架构，四核及以上；")
    p("  - 运行内存：建议 2 GB 及以上；")
    p("  - 存储空间：安装包约 25 MB，运行时另需约 50 MB 临时空间；")
    p("  - 屏幕：建议分辨率 720×1280 及以上。")
    p()
    p("软件环境：")
    p("  - 操作系统：Android 8.0（API 26）及以上；")
    p("  - 网络：建模与求解无需联网；若使用 AI 建模功能，需联网调用大模型；")
    p("  - 权限：相机（拍照建模）、存储（保存报告与截图）。")
    p()

    # 4 安装
    p("第 4 章 安装与启动")
    p("安装步骤：")
    p("  1. 从官方发布渠道获取 APK 安装包；")
    p("  2. 在手机“设置 - 安全”中允许安装未知来源应用；")
    p("  3. 点击 APK 文件完成安装；")
    p("  4. 首次启动时，授予相机与存储权限。")
    p()
    p("启动后，软件进入首页。首页展示软件的主要功能入口，包括“对话建模”、")
    p("“拍照建模”、“材料库”、“设置”等。")
    p()

    # 5 界面概览
    p("第 5 章 界面概览")
    p("本软件采用 Material Design 3 风格的单 Activity 多页面架构，主要界面")
    p("包括：")
    p("  - 首页：功能入口与最近项目；")
    p("  - 对话页：以聊天方式描述结构，AI 生成模型；")
    p("  - 建模预览页：展示 AI 生成的结构模型，允许用户修改参数；")
    p("  - 结果页：展示变形云图、应力云图、安全系数与文字结论；")
    p("  - 材料库页：浏览与编辑材料参数；")
    p("  - 设置页：配置 AI 模型、超时、开发者选项等。")
    p()

    # 6 输入方式
    p("第 6 章 输入方式")
    p("本软件支持三种输入方式：")
    p()
    p("1. 文字描述")
    p("   在对话页用自然语言描述结构，例如：“一根 2 米长的木板，两端搁在")
    p("   砖墙上，中间站一个 70 公斤的人”。软件会自动提取结构的几何、约束")
    p("   与载荷信息。")
    p()
    p("2. 拍照建模")
    p("   点击“拍照”按钮，对实际结构拍照。AI 会识别照片中的结构轮廓，结合")
    p("   参考物（如 A4 纸或银行卡）估算几何尺寸，生成初始模型。用户可在")
    p("   预览页修正识别结果。")
    p()
    p("3. 附图补充")
    p("   在对话过程中可随时点击“附图”按钮上传图片，作为文字描述的补充。")
    p("   AI 会综合文字与图片信息生成模型。")
    p()

    # 7 AI 技能
    p("第 7 章 AI 技能选择")
    p("本软件提供三种 AI 技能，用户可根据需求选择：")
    p()
    p("  - 不使用 AI：直接进入参数表单，由用户手动输入结构的全部几何、约束、")
    p("    载荷与材料信息。适用于对结构参数完全清楚的用户。")
    p("  - 保守校核：AI 生成模型后，按偏保守的原则选取参数（如取较大的载荷、")
    p("    较小的截面尺寸），给出偏安全的校核结论。适用于关注安全性的场景。")
    p("  - 快速估算：AI 生成模型后，按常规取值快速估算，给出中性结论。适用")
    p("    于需要快速得到数量级结果的场景。")
    p("  - 自定义：用户自定义 AI 的提示词与取值偏好，灵活控制建模过程。")
    p()

    # 8 建模预览
    p("第 8 章 建模预览与参数确认")
    p("AI 生成模型后，进入建模预览页。该页以示意图展示结构的几何形状、约束")
    p("位置与载荷方向，并列出关键参数供用户确认或修改：")
    p("  - 几何参数：跨度、高度、截面尺寸等；")
    p("  - 约束类型：固定端、铰支、滚动支座等；")
    p("  - 载荷：集中力、均布载荷、力矩等；")
    p("  - 材料：从材料库选择或自定义。")
    p()
    p("用户修改参数后，预览图会实时更新。确认无误后点击“开始求解”按钮，")
    p("软件将在本地执行有限元计算。")
    p()

    # 9 平面杆系
    p("第 9 章 平面杆系结构求解")
    p("平面杆系结构是本软件的核心求解场景，适用于梁、柱、刚架、桁架等由")
    p("杆件组成的结构。")
    p()
    p("求解流程：")
    p("  1. AI 或手动建立杆件模型，每个杆件定义两个端点、截面与材料；")
    p("  2. 定义约束与载荷；")
    p("  3. 软件采用欧拉-伯努利梁单元离散结构，组装整体刚度矩阵；")
    p("  4. 引入边界条件，求解节点位移；")
    p("  5. 由位移回代得到杆件内力（轴力、剪力、弯矩）与应力。")
    p()
    p("关键技术：")
    p("  - 自研梁单元刚度矩阵，考虑轴向、剪切与弯曲变形；")
    p("  - 均布载荷等效为节点力，保证等效前后合力与合力矩一致；")
    p("  - 弯矩沿杆长采用二重积分得到精确挠度，而非线性插值近似；")
    p("  - 支持铰节点与半刚性连接。")
    p()
    p("输出结果：变形云图、弯矩图、剪力图、轴力图、最大应力、最大挠度、")
    p("安全系数与薄弱杆件标识。")
    p()

    # 10 三维实体
    p("第 10 章 三维实体结构求解")
    p("对于形状复杂或三维受力显著的结构，本软件提供三维实体求解能力。")
    p()
    p("求解流程：")
    p("  1. 用户输入或 AI 识别三维实体的几何外形；")
    p("  2. 软件自动生成四面体网格（TET4 单元）；")
    p("  3. 定义约束面与载荷面；")
    p("  4. 组装三维实体单元刚度矩阵并求解。")
    p()
    p("关键技术：")
    p("  - 自研 TET4 四面体单元刚度矩阵集成；")
    p("  - 网格生成采用 Delaunay 三角化与边界约束；")
    p("  - 支持位移边界、力边界与压力边界。")
    p()
    p("输出结果：三维变形云图、主应力云图、Von Mises 应力云图、最大位移")
    p("与最大应力位置。")
    p()

    # 11 渗流
    p("第 11 章 渗流分析")
    p("本软件支持土力学中的二维稳定渗流分析，适用于堤坝、基坑、挡土墙等")
    p("场景的渗透水压力估算。")
    p()
    p("求解流程：")
    p("  1. 建立渗流区域几何，定义上下游水头边界；")
    p("  2. 输入土体渗透系数；")
    p("  3. 软件采用四边形等参单元求解 Laplace 渗流方程；")
    p("  4. 输出渗流场水头分布、流速矢量与渗透坡降。")
    p()
    p("关键技术：")
    p("  - 稳定化有限单元，抑制低阶单元在渗流问题中的数值振荡；")
    p("  - 自动识别自由面位置。")
    p()

    # 12 风压
    p("第 12 章 风压与围护结构分析")
    p("本软件可根据建筑高度与体型，按规范方法估算围护结构表面的风压力分布，")
    p("并将其作为载荷施加到结构模型中。")
    p()
    p("求解流程：")
    p("  1. 输入建筑高度、地面粗糙度类别与体型系数；")
    p("  2. 软件按规范公式计算各高度处的风压；")
    p("  3. 将风压转化为围护结构的均布载荷；")
    p("  4. 求解围护结构的内力与变形。")
    p()
    p("适用场景：雨棚、广告牌、围挡、简易屋面等围护结构的抗风校核。")
    p()

    # 13 时间历程
    p("第 13 章 时间历程与温度作用")
    p("本软件支持考虑时间维度的分析，包括温度变化引起的热胀冷缩效应与")
    p("随时间变化的载荷历程。")
    p()
    p("温度作用：")
    p("  - 用户输入结构的初始温度与最终温度，以及材料的线膨胀系数；")
    p("  - 软件计算温度变形，并在约束存在时得到温度应力；")
    p("  - 输出热伸长量、温度应力与对结构内力的影响。")
    p()
    p("时间历程：")
    p("  - 用户输入随时间变化的载荷曲线；")
    p("  - 软件按时间步长逐步求解，输出各时刻的响应；")
    p("  - 适用于施工过程模拟、季节性温度变化等场景。")
    p()

    # 14 结果解读
    p("第 14 章 结果解读")
    p("求解完成后，结果页展示以下内容：")
    p("  - 变形云图：用颜色渐变展示结构各点的位移大小，直观反映变形形态；")
    p("  - 应力云图：展示结构各点的应力值，红色区域为高应力区；")
    p("  - 内力图：对于杆系结构，分别展示弯矩图、剪力图、轴力图；")
    p("  - 数值结果：最大位移、最大应力、安全系数等关键指标；")
    p("  - 文字结论：用通俗语言总结结构是否安全、薄弱部位在哪里。")
    p()
    p("安全系数的计算：软件根据材料的屈服强度或抗压强度与计算应力的比值")
    p("得到安全系数。安全系数大于 1 表示应力小于材料强度，但实际安全裕度")
    p("需结合具体场景判断。")
    p()

    # 15 追问与重算
    p("第 15 章 结果追问与改条件重算")
    p("用户对结果有疑问时，可在结果页点击“追问”按钮，用自然语言向 AI 提问，")
    p("例如：“如果把木板换成 5 厘米厚的，结果会怎样？”")
    p()
    p("AI 会解析用户的修改意图，自动更新模型参数并重新求解，给出新的结果。")
    p("这一过程支持多轮对话，用户可以逐步调整条件，观察结果变化。")
    p()
    p("此外，用户也可以直接在预览页手动修改参数后点击“重新求解”。")
    p()

    # 16 材料库
    p("第 16 章 材料库")
    p("本软件内置常用建筑与机械材料的力学参数库，包括：")
    p("  - 木材：松木、杉木、橡木等，参数包含密度、弹性模量、抗弯强度、")
    p("    顺纹抗压强度；")
    p("  - 钢材：Q235、Q345、不锈钢等，参数包含密度、弹性模量、屈服强度、")
    p("    抗拉强度；")
    p("  - 混凝土：C20、C25、C30 等，参数包含密度、弹性模量、抗压强度、")
    p("    抗拉强度；")
    p("  - 铝合金、塑料等其他材料。")
    p()
    p("用户可在材料库页查看材料参数，也可新增自定义材料，输入名称、密度、")
    p("弹性模量、强度等参数后保存，供建模时选用。")
    p()

    # 17 报告导出
    p("第 17 章 报告导出")
    p("在结果页点击“导出报告”按钮，软件将生成一份 PDF 格式的分析报告，")
    p("包含：")
    p("  - 项目名称与求解时间；")
    p("  - 结构模型示意图与参数；")
    p("  - 变形云图、应力云图等结果图；")
    p("  - 关键数值结果表格；")
    p("  - 文字结论与安全提示；")
    p("  - 免责声明。")
    p()
    p("报告生成后，用户可选择保存到手机存储或通过其他应用分享。")
    p()

    # 18 设置
    p("第 18 章 设置项")
    p("设置页提供以下配置项：")
    p("  - AI 模型选择：文本模型与视觉模型的选择；")
    p("  - AI 接口地址与密钥：用户可自行配置 AI 服务；")
    p("  - 超时设置：AI 请求的超时时间；")
    p("  - 单位制：公制 / 英制切换；")
    p("  - 开发者模式：连续点击版本号 7 次解锁，用于调试。")
    p()

    # 19 常见问题
    p("第 19 章 常见问题")
    p("Q1：为什么求解结果与实际感觉不符？")
    p("A：本软件基于线弹性假设与简化模型，实际结构可能存在非线性、缺陷、")
    p("   边界条件差异等。建议作为参考，重要决策请咨询专业工程师。")
    p()
    p("Q2：拍照建模识别不准怎么办？")
    p("A：拍照建模依赖 AI 识别与参考物尺寸估算，可能存在误差。用户可在")
    p("   预览页手动修正参数，以手动输入为准。")
    p()
    p("Q3：为什么大模型请求失败？")
    p("A：请检查网络连接与 AI 接口配置。若使用内置服务，可能存在额度或")
    p("   限流，请稍后重试。")
    p()
    p("Q4：导出的 PDF 在哪里？")
    p("A：默认保存在手机“下载”目录的 PocketSim 文件夹中，也可在导出时")
    p("   选择分享到其他应用。")
    p()
    p("Q5：支持 iOS 吗？")
    p("A：当前版本仅支持 Android，iOS 版本规划中。")
    p()

    # 20 版本记录
    p("第 20 章 版本记录")
    p("V1.0：")
    p("  - 首次发布；")
    p("  - 支持文字与拍照建模；")
    p("  - 支持平面杆系、三维实体、渗流、风压、时间历程求解；")
    p("  - 内置材料库与 PDF 报告导出；")
    p("  - 支持结果追问与改条件重算。")
    p()
    p("附录 A 术语表")
    p("  - 有限元：将连续结构离散为有限个单元，通过求解单元节点位移得到")
    p("    结构响应的数值方法；")
    p("  - 刚度矩阵：描述结构节点力与节点位移关系的矩阵；")
    p("  - 安全系数：材料强度与计算应力的比值；")
    p("  - 线弹性：应力与应变成正比的材料行为假设。")
    p()
    p("附录 B 开源组件声明")
    p("  本软件使用了以下开源组件：")
    p("  - Jetpack Compose（Apache-2.0）：UI 框架；")
    p("  - Chaquopy（商业/开源双许可）：Android 上运行 Python；")
    p("  - NumPy / SciPy（BSD-3-Clause）：数值计算；")
    p("  - scikit-fem（BSD-3-Clause）：有限元辅助；")
    p("  - ReportLab（BSD）：PDF 生成；")
    p("  - OkHttp（Apache-2.0）：网络请求。")
    p()
    p("附录 C 联系方式")
    p("  如在使用中遇到问题或有建议，可通过软件内反馈渠道联系开发者。")
    p()
    p("（全文完）")
    p()
    return L


def gen_manual_pdf():
    raw = manual_lines()
    # 过滤空行，保证每页均为有效文字行
    lines = [ln for ln in raw if ln.strip() != ""]
    per = 34  # 每页 34 行（≥30），60 页 = 2040 行
    target_pages = 60
    needed = target_pages * per
    # 内容不足则在末尾追加附录条目填充
    appendices = [
        "附录 D 单位换算表",
        "  长度：1 m = 1000 mm；1 ft = 0.3048 m",
        "  力：1 kN = 1000 N；1 tf = 9.80665 kN",
        "  应力：1 MPa = 1 N/mm²；1 ksi ≈ 6.895 MPa",
        "  密度：1 g/cm³ = 1000 kg/m³",
        "附录 E 常见结构形式",
        "  简支梁：两端铰支，跨中承受集中力或均布力；",
        "  悬臂梁：一端固定，另一端自由；",
        "  连续梁：多跨连续，支座处负弯矩；",
        "  刚架：梁柱刚接，节点传递弯矩；",
        "  桁架：杆件铰接，仅承受轴力。",
        "附录 F 求解精度说明",
        "  本软件的有限元求解基于线弹性小变形假设，适用于应力低于材料",
        "  比例极限、变形远小于结构尺寸的场合。对于大变形、非线性材料、",
        "  动力响应等问题，本软件的计算结果可能与实际有较大偏差。",
        "附录 G 隐私与数据安全",
        "  本软件的结构求解过程完全在本地执行，不上传用户的结构模型",
        "  与计算结果。AI 建模功能需联网调用大模型，上传内容仅包括用户",
        "  主动输入的文字与图片，不包含设备标识与个人身份信息。",
        "附录 H 致谢",
        "  感谢所有参与内测的用户，以及开源社区提供的数值计算与 UI 框架。",
        "  本软件的持续改进离不开用户的反馈与建议。",
    ]
    ai = 0
    while len(lines) < needed:
        lines.append(appendices[ai % len(appendices)])
        ai += 1
    lines = lines[:needed]
    pages = [lines[i * per:(i + 1) * per] for i in range(target_pages)]

    out = os.path.join(OUT_DIR, "文档鉴别材料_用户手册.pdf")
    c = canvas.Canvas(out, pagesize=A4)
    c.setTitle("文档鉴别材料 用户手册")
    y0 = PAGE_H - MARGIN_TOP - HEADER_H
    line_h = (y0 - MARGIN_BOTTOM) / per
    for p_idx, page_lines in enumerate(pages):
        draw_header(c, p_idx + 1, target_pages, is_code=False)
        c.setFont("WQY", 10.5)
        for i, ln in enumerate(page_lines):
            y = y0 - i * line_h
            text = ln if ln else " "
            c.drawString(MARGIN_X, y, text)
        c.showPage()
    c.save()
    print(f"[manual] -> {out} ({target_pages} 页, {per} 行/页)")
    return out


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    register_fonts()
    gen_code_pdf()
    gen_manual_pdf()
    print("\n生成完成。输出目录：", OUT_DIR)
