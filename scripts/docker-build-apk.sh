#!/usr/bin/env bash
# 在容器里打 TVBox APK，产物拷到仓库 dist/。本机不装 Android 编译工具链。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${TVBOX_BUILD_IMAGE:-tvbox-android-build:local}"
TASK="${1:-assembleJava64Debug}"
OUT_DIR="${ROOT}/dist"

cd "$ROOT"

echo "==> 构建打包镜像: ${IMAGE}"
docker build --platform=linux/amd64 -t "${IMAGE}" -f docker/Dockerfile docker

echo "==> 容器内执行: ./gradlew ${TASK}"
# 挂源码只读不够：gradle 要写 build/、改临时文件。读写挂载，缓存放命名卷。
# pyramid 里 Windows 的 buildPython 路径在 Linux 容器里会让 Chaquopy 配置失败，构建前临时删掉。
docker run --rm --platform=linux/amd64 \
  -v "${ROOT}:/workspace" \
  -v tvbox-gradle-cache:/root/.gradle \
  -w /workspace \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  -e ANDROID_HOME=/opt/android-sdk \
  "${IMAGE}" \
  bash -lc "
    set -euo pipefail
    # 临时去掉 Windows 路径，构建结束后还原，避免弄脏工作区。
    cp pyramid/build.gradle /tmp/pyramid-build.gradle.bak
    sed -i '/buildPython.*python.exe/d' pyramid/build.gradle
    trap 'cp /tmp/pyramid-build.gradle.bak pyramid/build.gradle' EXIT
    chmod +x ./gradlew
    ./gradlew ${TASK} --no-daemon --build-cache --max-workers=2
  "

mkdir -p "${OUT_DIR}"
# Debug / Release 输出路径不同，统一收集到 dist/（兼容 macOS 自带 bash 3.2）
apks=()
while IFS= read -r line; do
  apks+=("$line")
done < <(find "${ROOT}/app/build/outputs/apk" -type f -name 'TVBox_*.apk' 2>/dev/null | sort)

if [ "${#apks[@]}" -eq 0 ]; then
  echo "没有找到 APK：app/build/outputs/apk"
  exit 1
fi

rm -f "${OUT_DIR}"/TVBox_*.apk
for apk in "${apks[@]}"; do
  cp -f "$apk" "${OUT_DIR}/"
  echo "产出: ${OUT_DIR}/$(basename "$apk") ($(du -h "$apk" | awk '{print $1}'))"
done

echo "==> 完成。安装示例: adb install -r dist/TVBox_debug-java64.apk"
