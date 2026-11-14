#!/usr/bin/env bash
# Rebrands a checkout of the desktop sources as Meowio.
# Rebuilt so the Intel Mac build carries the latest desktop changes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd -P)"
root="$1"
gradle="$root/composeApp/build.gradle.kts"
icons="$root/composeApp/src/desktopMain/resources/icons"

# Meowio features (Smart auto-play, Live TV, backup, insights, AI assistant, ...) merged into the
# desktop sources. Files here replace the pinned desktop copies; new files are added.
cp -R "$here/overlay/." "$root/composeApp/src/"

# One-tap downloads and the Downloads screen live in the phone app's sources and are shared as-is,
# so the phone, Mac and Windows apps always behave the same.
shared="$here/../../mobile/composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads"
for f in AutoDownload.kt DownloadPrefsStorage.kt DownloadsScreen.kt DownloadLocation.kt DownloadLocationUi.kt; do
  cp "$shared/$f" "$root/composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/$f"
done

# The Library > Downloads poster grid is shared too; the desktop Library screen is an overlay copy.
cp "$here/../../mobile/composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryDownloadsContent.kt" \
  "$root/composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryDownloadsContent.kt"

# New downloads follow the user's chosen download folder (Settings in the Downloads screen).
dl="$root/composeApp/src/desktopMain/kotlin/com/nuvio/app/features/downloads/DownloadsPlatformDownloader.desktop.kt"
sed -i.bak 's|get() = File(DesktopStorage.rootDir.resolve("downloads").also { it.createDirectories() }.toUri())|get() = DownloadLocation.directory()|' "$dl"
grep -q 'DownloadLocation.directory()' "$dl" || { echo "download folder patch did not apply" >&2; exit 1; }
rm -f "$dl.bak"

sed -i.bak \
  -e 's/packageName = "Nuvio"/packageName = "Meowio"/' \
  -e 's/bundleID = "com.nuvio.media.desktop"/bundleID = "com.meowio.desktop"/' \
  -e 's/vendor = "Nuvio Media"/vendor = "Meowio"/' \
  -e 's/"Nuvio-macOS-/"Meowio-macOS-/' \
  -e 's/"Nuvio-Windows-/"Meowio-Windows-/' \
  -e 's/"Nuvio-\$desktopReleasePackageVersion/"Meowio-$desktopReleasePackageVersion/' \
  -e 's/menuGroup = "Nuvio"/menuGroup = "Meowio"/' \
  -e 's/TargetFormat.Msi,/TargetFormat.Msi, TargetFormat.Exe,/' \
  "$gradle"
grep -q 'TargetFormat.Exe' "$gradle" || { echo "EXE target patch did not apply" >&2; exit 1; }
rm -f "$gradle.bak"

# App icons for Mac and Windows (and the in-app logo): the 3D Meowio cat. The overlay copy above also
# replaced every app-icon-<theme>-transparent.{png,ico,icns} and app_icon_* drawable, so the window icon,
# the icon picker and the runtime Mac/Windows icon updaters all use the cat too.
cp "$here/meowio-1024.png" "$icons/nuvio-app-icon-transparent.png"
cp "$here/../../windows/meowio.ico" "$icons/nuvio-app-icon-transparent.ico"
cp "$here/meowio.icns" "$icons/nuvio-app-icon-transparent.icns"
