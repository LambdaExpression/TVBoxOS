#!/usr/bin/env bash
# 模拟器 Extended Controls / Esc 在部分 ATV 镜像上不可靠时，用这条通路发返回键。
set -euo pipefail
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export PATH="${SDK_ROOT}/platform-tools:${PATH}"
adb shell input keyevent KEYCODE_BACK
echo "sent KEYCODE_BACK"
adb shell dumpsys window 2>/dev/null | awk '/mCurrentFocus/{print; exit}'
