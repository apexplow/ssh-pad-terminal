#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="8.9"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_HOME="${ROOT_DIR}/.gradle/gradle-${GRADLE_VERSION}"
GRADLE_ZIP="${ROOT_DIR}/.gradle/gradle-${GRADLE_VERSION}-bin.zip"
JDK_HOME="${ROOT_DIR}/.gradle/jdks/jdk-17.0.11+9"
JDK_TAR="${ROOT_DIR}/.gradle/jdks/temurin-17.tar.gz"

if [[ ! -x "${GRADLE_HOME}/bin/gradle" ]]; then
  mkdir -p "${ROOT_DIR}/.gradle"
  if [[ ! -f "${GRADLE_ZIP}" ]]; then
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "${GRADLE_ZIP}"
  fi
  unzip -q "${GRADLE_ZIP}" -d "${ROOT_DIR}/.gradle"
fi

if [[ ! -x "${JDK_HOME}/bin/java" ]]; then
  mkdir -p "${ROOT_DIR}/.gradle/jdks"
  if [[ ! -f "${JDK_TAR}" ]]; then
    curl -fsSL "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.11%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.11_9.tar.gz" -o "${JDK_TAR}"
  fi
  tar -xzf "${JDK_TAR}" -C "${ROOT_DIR}/.gradle/jdks"
fi

export JAVA_HOME="${JDK_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

exec "${GRADLE_HOME}/bin/gradle" "$@"
