FROM ghcr.io/cirruslabs/android-sdk:35@sha256:c724009e305b4607157287624033ab97f319af44c244bfc9f73b6293f3bb01b9 AS android

ENV ANDROID_HOME=/opt/android-sdk-linux \
    ANDROID_SDK_ROOT=/opt/android-sdk-linux

WORKDIR /workspace

FROM android AS release

USER root
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        autoconf automake libtool pkg-config curl git \
        build-essential yasm nasm gperf texinfo bison ragel \
        python3 python3-pip unzip zip wget meson ninja-build \
        doxygen cmake autogen autopoint gettext groff \
        gtk-doc-tools libtasn1-bin openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    ANDROID_NDK_ROOT=/opt/android-sdk-linux/ndk/26.2.11394342 \
    PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/opt/android-sdk-linux/cmdline-tools/latest/bin:$PATH

RUN yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "ndk;26.2.11394342"
