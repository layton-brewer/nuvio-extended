#!/usr/bin/env bash
# Rebrands a checkout of the desktop sources as Meowio.
# Rebuilt so the Intel Mac build carries the latest desktop changes.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd -P)"
root="$1"
gradle="$root/composeApp/build.gradle.kts"
icons="$root/composeApp/src/desktopMain/resources/icons"

# Shared sources have one owner in mobile; platform-specific changes stay in the overlay.
shared="$here/../../mobile/composeApp/src"
manifest="$here/shared-sources.txt"
while IFS= read -r path || [[ -n "$path" ]]; do
  [[ -z "$path" || "$path" == \#* ]] && continue
  [[ -f "$shared/$path" ]] || { echo "Missing shared source: $path" >&2; exit 1; }
  [[ ! -e "$here/overlay/$path" ]] || { echo "Shared source also exists in overlay: $path" >&2; exit 1; }
done < "$manifest"

cp -R "$here/overlay/." "$root/composeApp/src/"
while IFS= read -r path || [[ -n "$path" ]]; do
  [[ -z "$path" || "$path" == \#* ]] && continue
  mkdir -p "$(dirname "$root/composeApp/src/$path")"
  cp "$shared/$path" "$root/composeApp/src/$path"
done < "$manifest"

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
