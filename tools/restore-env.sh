#!/usr/bin/env bash
# 沙箱环境恢复脚本（一键重建构建环境）
#
# 背景：沙箱会不定期清理 /opt、/root/.gradle、/root/.local/share/mise 等目录，
#       导致构建环境丢失。本脚本把可持久化的组件全部安置在 /workspace 内，
#       并重建必要的全局配置，使构建环境具备"自愈"能力。
#
# 用法：bash tools/restore-env.sh [--full]
#   默认：只重建丢失的组件（幂等，可反复执行）
#   --full：强制重装（忽略已有目录）
#
# 产出位置（均在持久工作区内）：
#   /workspace/android-sdk        Android SDK（platforms;android-34 + build-tools;34.0.0）
#   /workspace/.mise              mise 数据目录（Python 3.10 构建解释器）
#   /root/.gradle/gradle.properties  Gradle 代理配置（必须用 systemProp，Gradle 不读环境变量）

set -euo pipefail
FULL="${1:-}"

# ------------------------------------------------------------------
# 1. Gradle 代理（Gradle 不读 HTTP_PROXY 环境变量，必须写 systemProp）
# ------------------------------------------------------------------
if [[ ! -f /root/.gradle/gradle.properties || "$FULL" == "--full" ]]; then
    mkdir -p /root/.gradle
    cat > /root/.gradle/gradle.properties << 'EOF'
# 由 tools/restore-env.sh 生成（沙箱代理必须用 systemProp，Gradle 不读环境变量）
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.nonProxyHosts=localhost|127.0.0.1
EOF
    echo "[ok] gradle.properties 代理配置已写入"
fi

# ------------------------------------------------------------------
# 2. Android SDK → /workspace/android-sdk（工作区内，不被清理）
# ------------------------------------------------------------------
SDK=/workspace/android-sdk
if [[ ! -d "$SDK/platforms/android-34" || "$FULL" == "--full" ]]; then
    echo "[..] 安装 Android SDK 到 $SDK"
    mkdir -p "$SDK/cmdline-tools"
    cd /tmp
    curl -sSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    rm -rf "$SDK/cmdline-tools/latest"
    unzip -q cmdtools.zip -d "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    # pipefail 下 yes 会因 SIGPIPE 返回非零，属预期，忽略
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
        "platforms;android-34" "build-tools;34.0.0" > /dev/null
    echo "[ok] Android SDK 就绪"
else
    echo "[skip] Android SDK 已存在"
fi

# ------------------------------------------------------------------
# 3. Python 3.10（Chaquopy buildPython 锁 3.10：chaquo 仓库 scipy wheel 仅 cp310）
#    数据目录放 /workspace/.mise，不被清理
# ------------------------------------------------------------------
export MISE_DATA_DIR=/workspace/.mise
PY_BIN=$(mise where python@3.10 2>/dev/null || true)
if [[ -z "$PY_BIN" || "$FULL" == "--full" ]]; then
    echo "[..] 安装 Python 3.10（MISE_DATA_DIR=$MISE_DATA_DIR）"
    mise install python@3.10
    PY_BIN=$(mise where python@3.10)
fi
echo "[ok] Python 3.10: $PY_BIN"

# ------------------------------------------------------------------
# 4. JDK 17（通常幸存；丢了也重装）
# ------------------------------------------------------------------
if [[ -z "$(mise where java@17 2>/dev/null || true)" ]]; then
    echo "[..] 安装 JDK 17"
    mise install java@17
fi
echo "[ok] JDK 17: $(mise where java@17)"

# ------------------------------------------------------------------
# 5. local.properties 指向工作区内的 SDK
# ------------------------------------------------------------------
echo "sdk.dir=$SDK" > "$(dirname "$0")/../android/local.properties"
echo "[ok] android/local.properties → $SDK"

cat << 'EOF'

恢复完成。构建命令：
  export JAVA_HOME=$(mise where java@17)
  export ANDROID_HOME=/workspace/android-sdk ANDROID_SDK_ROOT=/workspace/android-sdk
  export PATH=$(mise where python@3.10)/bin:$PATH
  cd /workspace/pocketsim/android
  # -PchaquopyProxy 透传沙箱代理给 Chaquopy 的 pip 子进程（CI/直连环境不要传）
  gradle :app:assembleDebug -PchaquopyProxy=http://127.0.0.1:18080
  # 其他目标：:app:assembleRelease / :app:testDebugUnitTest（同样追加 -PchaquopyProxy=...）
EOF
