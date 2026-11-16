#!/system/bin/sh
# Run from the extracted project directory using: sh build-aar.sh
set -eu
cd "$(dirname "$0")"
case "${1:-debug}" in
  debug) VARIANT=Debug; NAME=Bcore-debug.aar ;;
  release) VARIANT=Release; NAME=Bcore-release.aar ;;
  *) echo 'Usage: sh build-aar.sh [debug|release]' >&2; exit 2 ;;
esac
sh ./gradlew ":Bcore:assemble${VARIANT}" --no-daemon --stacktrace
mkdir -p sdk-dist
cp "Bcore/build/outputs/aar/$NAME" "sdk-dist/$NAME"
cp consumer-proguard-rules.pro sdk-dist/
cp dependencies.gradle sdk-dist/
echo 'SUCCESS. One AAR (Bcore + Black Reflection) is in sdk-dist/'
ls -lh sdk-dist/
