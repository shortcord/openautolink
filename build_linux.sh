#!/usr/bin/env bash
set -euo pipefail

readonly _keystorePath="./secrets/upload-key.jks"
readonly _versionPath="./secrets/version.properties"

readonly _currentDate=$(date +"%Y-%m-%d-%s")
readonly _buildOutputDir="./build/${_currentDate}"
readonly _currentGitRev=$(git rev-parse --short HEAD)

export ANDROID_HOME="/opt/android-sdk/"
export ANDROID_NDK_HOME="/opt/android-sdk/ndk/28.2.13676358"

export UPLOAD_STORE_PASSWORD="password"
export UPLOAD_KEY_ALIAS="upload"
export UPLOAD_KEY_PASSWORD="password"

if [ ! -f "${_keystorePath}" ]; then
    echo "Keystore file not found at ${_keystorePath}. Creating..."
    keytool \
        -v \
        -genkeypair \
        -storetype PKCS12 \
        -alias "${UPLOAD_KEY_ALIAS}" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 9125 \
        -storepass "${UPLOAD_STORE_PASSWORD}" \
        -keypass "${UPLOAD_KEY_PASSWORD}" \
        -dname "CN=Android Upload Key, OU=Personal, O=Personal, L=Chesterfield, S=Missouri, C=US" \
        -keystore "${_keystorePath}"
fi

echo "Cleaning up old build artifacts..."
./gradlew clean --no-daemon
./gradlew --stop

for i in .gradle /tmp/oal-ndk-deps /tmp/oal-aasdk-android-build ./app/src/main/cpp/third_party/; do
    if [ -d "$i" ]; then
        echo "Removing $i directory..."
        rm -rf "$i"
    else
        echo "No $i directory found. Skipping cleanup."
    fi
done

echo "Generate Build Output Directory..."
mkdir -p "${_buildOutputDir}"
mkdir -p "${_buildOutputDir}/logs/"

echo "Generating version properties..."
if [ ! -f "${_versionPath}" ]; then
    echo "Version properties file not found at ${_versionPath}. Creating..."
    printf "%s\n" \
        "appVersionCode=1" \
        "appVersionName=${_currentGitRev}" \
        "appId=com.shortcord.openautolink.companion" \
        "\n" \
        "oalVersionCode=1" \
        "oalVersionName=${_currentGitRev}" \
        "oalAppId=com.shortcord.openautolink.app" \
        > "${_versionPath}"
else 
    echo "Version properties file already exists at ${_versionPath}."
    _setVersionName=$(grep -E '^appVersionName=' "${_versionPath}" | cut -d= -f2)

    if [ "${_setVersionName}" != "${_currentGitRev}" ]; then
        _currentVersionCode=$(grep -E '^appVersionCode=' "${_versionPath}" | cut -d= -f2)
        _newVersionCode=$(( _currentVersionCode + 1 ))

        echo "Updating version code in ${_versionPath} to ${_newVersionCode}..."
        sed -i "s/^appVersionCode=.*/appVersionCode=${_newVersionCode}/" "${_versionPath}"
        sed -i "s/^oalVersionCode=.*/oalVersionCode=${_newVersionCode}/" "${_versionPath}"

        echo "Updating version name in ${_versionPath} to match current git revision (${_currentGitRev})..."
        sed -i "s/^appVersionName=.*/appVersionName=${_currentGitRev}/" "${_versionPath}"
        sed -i "s/^oalVersionName=.*/oalVersionName=${_currentGitRev}/" "${_versionPath}"
    else
        echo "Version name in ${_versionPath} already matches current git revision (${_currentGitRev}). No update needed."
    fi
fi

echo "Configuring OpenSSL..."
bash ./scripts/build-openssl-android.sh arm64-v8a > "${_buildOutputDir}/logs/build-openssl-android-arm64-v8a.log" 2>&1
bash ./scripts/build-openssl-android.sh x86_64 > "${_buildOutputDir}/logs/build-openssl-android-x86_64.log" 2>&1

echo "Configuring Android NDK..."
bash ./scripts/setup-ndk-deps.sh arm64-v8a > "${_buildOutputDir}/logs/setup-ndk-deps-arm64-v8a.log" 2>&1
bash ./scripts/setup-ndk-deps.sh x86_64 > "${_buildOutputDir}/logs/setup-ndk-deps-x86_64.log" 2>&1

echo "Configuring AASDK..."
bash ./scripts/build-aasdk-android.sh arm64-v8a > "${_buildOutputDir}/logs/build-aasdk-android-arm64-v8a.log" 2>&1
bash ./scripts/build-aasdk-android.sh x86_64 > "${_buildOutputDir}/logs/build-aasdk-android-x86_64.log" 2>&1

echo "Building OpenAutoLink Companion..."
if ./gradlew :companion:bundleRelease \
    -PappVersionCode="$(grep -E '^appVersionCode=' "${_versionPath}" | cut -d= -f2)" \
    -PappVersionName="$(grep -E '^appVersionName=' "${_versionPath}" | cut -d= -f2)" \
    -PappId="$(grep -E '^appId=' "${_versionPath}" | cut -d= -f2)" \
    | tee "${_buildOutputDir}/logs/assemble-companion.log" 2>&1; then
    echo "Companion build succeeded. Moving Output to Build Directory..."
    cp -v "./companion/build/outputs/bundle/release/companion-release.aab" "${_buildOutputDir}/companion-release.aab"
fi

echo "Building OpenAutoLink..."
if ./gradlew :app:bundleRelease \
    -PoalVersionCode="$(grep -E '^oalVersionCode=' "${_versionPath}" | cut -d= -f2)" \
    -PoalVersionName="$(grep -E '^oalVersionName=' "${_versionPath}" | cut -d= -f2)" \
    -PoalAppId="$(grep -E '^oalAppId=' "${_versionPath}" | cut -d= -f2)" \
    | tee "${_buildOutputDir}/logs/assemble-app.log" 2>&1; then
    echo "OpenAutoLink build succeeded. Moving Output to Build Directory..."
    cp -v "./app/build/outputs/bundle/release/app-release.aab" "${_buildOutputDir}/openautolink-aaos.aab"
fi

./gradlew --stop
