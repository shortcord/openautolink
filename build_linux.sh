#!/usr/bin/env bash
set -euo pipefail

readonly _keystorePath="./secrets/upload-key.jks"
readonly _versionPath="./secrets/version.properties"

readonly _currentDate=$(date +"%Y-%m-%d-%s")
readonly _buildOutputDir="./build/${_currentDate}"
readonly _currentGitRev=$(git rev-parse --short HEAD)

_doClean=false
_prepareOnly=false
_cleanOnly=false

usage() {
    cat <<'EOF'
Usage: ./build_linux.sh [--clean] [--prepare] [--clean-only]

  --clean   Removes old build artifacts and caches (gradle clean, .gradle, tmp dirs, etc.)
  --prepare Build Linux-native dependencies (OpenSSL/Boost/AASDK) only; does not run Gradle bundles.
  --clean-only  Only remove old artifacts/caches and exit (no Gradle invocation; safe for running after `./gradlew clean`).
EOF
}

for arg in "$@"; do
    case "${arg}" in
        --clean) _doClean=true ;;
        --prepare) _prepareOnly=true ;;
        --clean-only)
            _doClean=true
            _cleanOnly=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: ${arg}" >&2
            usage >&2
            exit 2
            ;;
    esac
done

export ANDROID_HOME="/opt/android-sdk/"
export ANDROID_NDK_HOME="/opt/android-sdk/ndk/28.2.13676358"

export UPLOAD_STORE_PASSWORD="password"
export UPLOAD_KEY_ALIAS="upload"
export UPLOAD_KEY_PASSWORD="password"

mkdir -p "$(dirname "${_keystorePath}")"

if [ "${_prepareOnly}" = false ]; then
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
fi

safe_rm_dir() {
    local dir="$1"
    if [ -z "${dir}" ] || [ "${dir}" = "/" ] || [ "${dir}" = "." ] || [ "${dir}" = ".." ]; then
        echo "Refusing to remove unsafe directory: '${dir}'" >&2
        exit 3
    fi

    if [ -d "${dir}" ]; then
        echo "Removing ${dir} directory..."
        rm -rf "${dir}"
    else
        echo "No ${dir} directory found. Skipping cleanup."
    fi
}

if [ "${_doClean}" = true ]; then
    echo "Cleaning up old build artifacts (--clean)..."
    if [ "${_cleanOnly}" = false ]; then
        ./gradlew clean --no-daemon
        ./gradlew --stop
    fi

    for i in .gradle /tmp/oal-ndk-deps /tmp/oal-aasdk-android-build ./app/src/main/cpp/third_party/; do
        safe_rm_dir "$i"
    done

    if [ "${_cleanOnly}" = true ]; then
        echo "Cleanup complete (--clean-only)."
        exit 0
    fi
else
    echo "Skipping cleanup (pass --clean to remove old build artifacts)."
fi

echo "Generate Build Output Directory..."
mkdir -p "${_buildOutputDir}"
mkdir -p "${_buildOutputDir}/logs/"

if [ "${_prepareOnly}" = false ]; then
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
fi

echo "Configuring OpenSSL..."
if [ ! -d "./app/src/main/cpp/third_party/openssl/arm64-v8a" ]; then
    bash ./scripts/build-openssl-android.sh arm64-v8a 2>&1 | tee "${_buildOutputDir}/logs/build-openssl-android-arm64-v8a.log"
fi

if [ ! -d "./app/src/main/cpp/third_party/openssl/x86_64" ]; then
    bash ./scripts/build-openssl-android.sh x86_64 2>&1 | tee "${_buildOutputDir}/logs/build-openssl-android-x86_64.log"
fi

if [ ! -d "/tmp/oal-ndk-deps" ]; then
    echo "Configuring Android NDK..."
    bash ./scripts/setup-ndk-deps.sh arm64-v8a 2>&1 | tee "${_buildOutputDir}/logs/setup-ndk-deps-arm64-v8a.log"
    bash ./scripts/setup-ndk-deps.sh x86_64 2>&1 | tee "${_buildOutputDir}/logs/setup-ndk-deps-x86_64.log"
fi

if [ ! -d "/tmp/oal-aasdk-android-build" ]; then
    echo "Configuring AASDK..."
    bash ./scripts/build-aasdk-android.sh arm64-v8a 2>&1 | tee "${_buildOutputDir}/logs/build-aasdk-android-arm64-v8a.log"
    bash ./scripts/build-aasdk-android.sh x86_64 2>&1 | tee "${_buildOutputDir}/logs/build-aasdk-android-x86_64.log"
fi

if [ "${_prepareOnly}" = true ]; then
    echo "Dependency preparation complete (--prepare)."
    exit 0
fi

echo "Building OpenAutoLink Companion..."
if ./gradlew :companion:bundleRelease \
    -PappVersionCode="$(grep -E '^appVersionCode=' "${_versionPath}" | cut -d= -f2)" \
    -PappVersionName="$(grep -E '^appVersionName=' "${_versionPath}" | cut -d= -f2)" \
    -PappId="$(grep -E '^appId=' "${_versionPath}" | cut -d= -f2)" \
    2>&1 | tee "${_buildOutputDir}/logs/assemble-companion.log"; then
    echo "Companion build succeeded. Moving Output to Build Directory..."
    _companionOut="./companion/build/outputs/bundle/release/companion-release.aab"
    if [ ! -f "${_companionOut}" ]; then
        echo "Expected output not found: ${_companionOut}" >&2
        exit 1
    fi
    cp -v "${_companionOut}" "${_buildOutputDir}/companion-release.aab"
fi

echo "Building OpenAutoLink..."
if ./gradlew :app:bundleRelease \
    -PoalVersionCode="$(grep -E '^oalVersionCode=' "${_versionPath}" | cut -d= -f2)" \
    -PoalVersionName="$(grep -E '^oalVersionName=' "${_versionPath}" | cut -d= -f2)" \
    -PoalGitHash="${_currentGitRev}" \
    -PoalAppId="$(grep -E '^oalAppId=' "${_versionPath}" | cut -d= -f2)" \
    2>&1 | tee "${_buildOutputDir}/logs/assemble-app.log"; then
    echo "OpenAutoLink build succeeded. Moving Output to Build Directory..."
    _appOut="./app/build/outputs/bundle/release/app-release.aab"
    if [ ! -f "${_appOut}" ]; then
        echo "Expected output not found: ${_appOut}" >&2
        exit 1
    fi
    cp -v "${_appOut}" "${_buildOutputDir}/openautolink-aaos.aab"
fi

./gradlew --stop

# Symlink 'latest' to current build output for easy access; update if it already exists
if [ ! -L "${_buildOutputDir}/../latest" ]; then
    echo "Creating 'latest' symlink to current build output..."
    ln -s "$(basename "${_buildOutputDir}")" "${_buildOutputDir}/../latest"
else
    echo "Updating 'latest' symlink to current build output..."
    rm -f "${_buildOutputDir}/../latest"
    ln -s "$(basename "${_buildOutputDir}")" "${_buildOutputDir}/../latest"
fi
