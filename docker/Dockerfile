# TVBox 打包镜像：与 .github/workflows/release.yml 对齐
# - sdkmanager 需要 Java 17
# - AGP 7.2 / Gradle 构建使用 Java 11
# Apple Silicon 上用 linux/amd64，避免 arm64 缺 Android 组件。
FROM --platform=linux/amd64 docker.m.daocloud.io/library/eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk

# Python 给 Chaquopy 配置阶段用；即使只打 java 口味，:pyramid 配置时也会探 Python。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl unzip git python3 python3-pip openjdk-11-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_17_HOME=/opt/java/openjdk \
    JAVA_11_HOME=/usr/lib/jvm/java-11-openjdk-amd64

# 安装 Android cmdline-tools，并用 Java 17 拉齐 platform / build-tools。
ARG CMDLINE_TOOLS_URL=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && curl -fsSL -o /tmp/cmdline-tools.zip "${CMDLINE_TOOLS_URL}" \
    && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
    && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip \
    && export JAVA_HOME="${JAVA_17_HOME}" \
    && export PATH="${JAVA_HOME}/bin:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${PATH}" \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --sdk_root=${ANDROID_SDK_ROOT} \
        "platforms;android-33" \
        "build-tools;30.0.3" \
        "platform-tools"

# 默认切回 Java 11，给 Gradle / AGP 7.2 用。
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
    PATH=/usr/lib/jvm/java-11-openjdk-amd64/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

WORKDIR /workspace

CMD ["./gradlew", "assembleJava64Debug", "--no-daemon", "--build-cache"]
