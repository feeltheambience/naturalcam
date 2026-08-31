#!/usr/bin/env bash
# Выкатка нового обновления NaturalCam по воздуху (OTA).
# Хостинг: прямой http на сервере Дениса (188.227.86.219:8404), без посредников.
#
# Использование:  bash ota/push_update.sh <versionCode> <versionName> "заметки"
# Пример:         bash ota/push_update.sh 5 0.5 "Починил ориентацию превью"
#
# ПЕРЕД запуском:
#   1) подними versionCode/versionName в app/build.gradle.kts на те же значения
#   2) собери RELEASE (маленький APK):  gradle assembleRelease

set -e
CODE="${1:?versionCode}"; NAME="${2:?versionName}"; NOTES="${3:-Обновление}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-universal-release.apk"
[ -f "$APK" ] || { echo "Нет release APK: $APK — сначала: gradle assembleRelease"; exit 1; }

REMOTE_DIR="/root/naturalcam-ota"
BASE="http://188.227.86.219:8404"
APK_NAME="NaturalCam-$NAME.apk"

echo ">>> заливаю $APK_NAME ($(du -h "$APK" | cut -f1))"
scp "$APK" "srv188:$REMOTE_DIR/$APK_NAME"

echo ">>> обновляю version.json (versionCode=$CODE)"
cat > /tmp/nc_version.json <<EOF
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "url": "$BASE/$APK_NAME",
  "notes": "$NOTES"
}
EOF
scp /tmp/nc_version.json "srv188:$REMOTE_DIR/version.json"

echo ">>> проверка"
curl -s "$BASE/version.json"; echo
echo "Готово. У пользователей с версией < $CODE появится баннер обновления."
