#!/usr/bin/env bash
# 启动本机 Android TV 模拟器，并安装 dist/ 里的 java64 Debug APK。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AVD_NAME="${TVBOX_AVD:-TVBox_ATV_33}"
APK="${1:-$ROOT/dist/TVBox_debug-java64.apk}"

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="${SDK_ROOT}/platform-tools:${SDK_ROOT}/emulator:${SDK_ROOT}/cmdline-tools/latest/bin:${PATH}"

if [ ! -x "${SDK_ROOT}/emulator/emulator" ]; then
  echo "找不到模拟器: ${SDK_ROOT}/emulator/emulator"
  exit 1
fi
if [ ! -f "$APK" ]; then
  echo "找不到 APK: $APK"
  echo "先跑: ./scripts/docker-build-apk.sh"
  exit 1
fi

# 已有在线设备则复用。
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "==> 启动模拟器 ${AVD_NAME}"
  # 双重 fork 脱离当前 shell 进程组，避免 IDE/Agent 结束会话时把模拟器一起杀掉。
  # TVBOX_EMU_NO_WINDOW=1 时无头；默认有窗口方便本机点测。
  NO_WINDOW="${TVBOX_EMU_NO_WINDOW:-0}" python3 - "$SDK_ROOT" "$AVD_NAME" <<'PY'
import os, sys, subprocess
sdk, avd = sys.argv[1], sys.argv[2]
emu = os.path.join(sdk, "emulator", "emulator")
# QtRawKeyboardInput：ATV 镜像上默认键盘转发经常丢 Esc/返回。
args = [
    emu, "-avd", avd,
    "-netdelay", "none", "-netspeed", "full",
    "-no-snapshot", "-no-audio",
    "-gpu", "swiftshader_indirect",
    "-feature", "QtRawKeyboardInput",
]
if os.environ.get("NO_WINDOW") == "1":
    args += ["-no-window", "-no-boot-anim"]
log = open("/tmp/tvbox-emulator.log", "w")
if os.fork() > 0:
    raise SystemExit(0)
os.setsid()
if os.fork() > 0:
    raise SystemExit(0)
os.chdir("/")
os.umask(0)
devnull = open(os.devnull, "r")
os.dup2(devnull.fileno(), 0)
os.dup2(log.fileno(), 1)
os.dup2(log.fileno(), 2)
env = os.environ.copy()
env["ANDROID_SDK_ROOT"] = sdk
env["ANDROID_HOME"] = sdk
proc = subprocess.Popen(args, env=env, close_fds=True)
open("/tmp/tvbox-emulator.pid", "w").write(str(proc.pid))
raise SystemExit(0)
PY
  sleep 1
  echo "模拟器 pid=$(cat /tmp/tvbox-emulator.pid 2>/dev/null || echo '?') 日志=/tmp/tvbox-emulator.log"
fi

echo "==> 等待 adb device / boot_completed"
for i in $(seq 1 90); do
  st="$(adb devices 2>/dev/null | awk '/emulator-/{print $2; exit}')"
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$st" = "device" ] && [ "$boot" = "1" ]; then
    break
  fi
  sleep 2
done
st="$(adb devices 2>/dev/null | awk '/emulator-/{print $2; exit}')"
boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
if [ "$st" != "device" ] || [ "$boot" != "1" ]; then
  echo "模拟器启动超时，看 /tmp/tvbox-emulator.log"
  exit 1
fi

echo "==> 安装 $APK"
adb install -r -t "$APK"
echo "==> 启动 HomeActivity"
adb shell am start -n com.github.tvbox.osc.jun/com.github.tvbox.osc.ui.activity.HomeActivity || \
  adb shell monkey -p com.github.tvbox.osc.jun -c android.intent.category.LEANBACK_LAUNCHER 1
echo "==> app_pid=$(adb shell pidof com.github.tvbox.osc.jun | tr -d '\r')"
# 模拟器里的 10.0.2.15 是 guest 内网地址，宿主机浏览器到不了。
# 用 adb forward 把本机 9978 转到模拟器，远程配置页访问 http://127.0.0.1:9978/
adb forward --remove tcp:9978 >/dev/null 2>&1 || true
adb forward tcp:9978 tcp:9978 >/dev/null
echo "==> 远程配置请用: http://127.0.0.1:9978/  （不要用弹窗里的 10.0.2.15）"
echo "==> 完成"
