# Credits and licenses

Meowio is free software under the [GNU General Public License v3.0](LICENSE).

## Upstream projects

Meowio would not exist without the work of the Nuvio team and the open-source projects below. Upstream copyright notices and license files are preserved in the source tree (see `tv/LICENSE`, `mobile/LICENSE` and the notices inside each folder).

| Part of Meowio | Based on | License |
| --- | --- | --- |
| Android phone/tablet and iOS/iPadOS app (`mobile/`) | [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) | GPL-3.0 |
| Android TV app (`tv/`) | [NuvioTV](https://github.com/NuvioMedia/NuvioTV) | GPL-3.0 |
| Mac and Windows apps (`apple/macos/`, built on top of a pinned checkout in CI) | [NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) | GPL-3.0 |
| Apple TV app (`tvos/`) | Original SwiftUI app following Nuvio's features; uses [MPVKit](https://github.com/mpvkit/MPVKit) / mpv for formats AVPlayer cannot open | see MPVKit / mpv |
| Default Live TV channel list | [Free-TV/IPTV](https://github.com/Free-TV/IPTV) | see that project |
| Live TV playlists, guide, backup-channel failover and the Live TV VPN (ideas and design) | [NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV) by homelessbrian, VPN design by ghbarker | GPL-3.0 |
| Live TV VPN library (`native/livetv-vpn`) | Meowio's own Go code on [wireguard-go](https://git.zx2c4.com/wireguard-go) and [gVisor](https://github.com/google/gvisor) netstack | MIT / Apache-2.0 |
| Audio decoder downmix module (`tv/ffmpeg-decoder-downmix`) | AndroidX Media3 `decoder_ffmpeg`, Kodi | Apache-2.0 / GPL-2.0+ (see `NOTICE.md` there) |

Meowio also builds on many third-party libraries (AndroidX, Media3/ExoPlayer, Jetpack Compose, Kotlin, Ktor, Coil and others) under their own licenses. The apps list them under **Settings > About > Licenses and attributions**.

## Not affiliated

Meowio is an independent fork. It is not affiliated with, sponsored by or endorsed by the Nuvio team, Tapframe, Trakt, or any add-on or content provider. Nuvio and other names belong to their respective owners.
