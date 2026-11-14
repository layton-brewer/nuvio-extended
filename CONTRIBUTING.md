# Contributing to Meowio

Thanks for helping! Bug reports, ideas and pull requests are welcome.

- **Bugs and ideas:** open an [issue](https://github.com/layton-brewer/Meowio/issues/new/choose). Remove credentials and private URLs first.
- **Pull requests:** keep them focused and describe what you changed and how you checked it. Android phone and TV live in `mobile/` and `tv/`, the Mac and Windows overlay in `apple/macos/overlay`, and the Apple TV app in `tvos/`.
- **Parity:** features should land on all apps (Android phone, Android TV, iOS, Apple TV, Mac, Windows) unless they are platform-specific; downloads are phone and desktop only.
- **No secrets:** never commit keystores, API keys, `local.properties` or account-server addresses. Build-time secrets come from repository secrets only.
- **Content:** we will not accept changes that bundle, host or link to copyrighted media or default piracy add-ons.
- **License:** contributions are licensed under GPL v3, like the rest of the project.

Build instructions are in the [README](README.md#build-from-source).

## Shared desktop sources

Mac and Windows builds apply `apple/macos/apply-overlay.sh` to the pinned desktop
checkout. Files listed in `apple/macos/shared-sources.txt` are copied unchanged
from `mobile/composeApp/src`; edit them there. Keep desktop-specific implementations
in `apple/macos/overlay`. A file must not appear in both places.

When a shared file needs desktop-specific behavior, remove its manifest entry and
add the desktop implementation to the overlay. When adding a new shared file, list
its path relative to `mobile/composeApp/src`.

Run the overlay regression checks without an Android SDK or desktop checkout:

```bash
python3 apple/macos/test_shared_sources.py
```
