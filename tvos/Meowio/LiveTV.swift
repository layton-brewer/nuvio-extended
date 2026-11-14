import SwiftUI
import AVKit
import Compression

// MARK: - Models

struct LiveChannel: Identifiable, Hashable {
    var id: String
    var name: String
    var url: String
    var logo: String? = nil
    var group: String? = nil
    var guideId: String? = nil
    var headers: [String: String] = [:]
    var xtreamStreamId: String? = nil
}

struct LivePlaylist {
    var channels: [LiveChannel]
    var guideUrls: [String] = []
}

struct LiveProgramme: Hashable {
    var title: String
    var start: Date
    var stop: Date
}

struct LiveNowNext: Hashable {
    var now: LiveProgramme?
    var next: LiveProgramme?
}

enum LiveTvError: String {
    case invalidSource = "The address or login is missing or is not a web address."
    case network = "The provider could not be reached."
    case notAPlaylist = "That address did not return a channel playlist."
    case loginFailed = "The provider rejected the username or password."
    case noChannels = "The playlist loaded but holds no channels."
}

// MARK: - Built-in channels

enum LiveTvDefaults {
    static let playlistURL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    private static let guideTagByGroup: [String: String] = [
        "USA": "US", "UK": "UK", "Canada": "CA", "Australia": "AU", "Ireland": "IE",
        "Germany": "DE", "France": "FR", "Spain": "ES", "Italy": "IT", "Poland": "PL",
        "Netherlands": "NL", "Belgium": "BE", "Austria": "AT", "Switzerland": "CH",
        "Sweden": "SE", "Norway": "NO", "Denmark": "DK", "Finland": "FI", "Portugal": "PT",
        "Brazil": "BR", "Mexico": "MX", "Argentina": "AR", "Chile": "CL", "India": "IN",
        "日本 / Japan": "JP", "Korea": "KR", "Turkey": "TR", "Greece": "GR", "Hungary": "HU",
        "Czech Republic": "CZ", "Romania": "RO", "Croatia": "HR", "Albania": "AL",
        "Bosnia and Herzegovina": "BA", "Saudi Arabia": "SA", "Nigeria": "NG", "Egypt": "EG",
        "Lithuania": "LT", "Latvia": "LV", "Serbia": "RS", "Slovakia": "SK", "Israel": "IL",
        "Peru": "PE", "Cyprus": "CY", "Costa Rica": "CR", "Bulgaria": "BG", "Kenya": "KE",
        "Hong Kong": "HK", "Malta": "MT", "Indonesia": "ID", "Dominican Republic": "DO",
    ]
    private static let extraGroupByCountry = ["CN": "China", "RU": "Russia", "UA": "Ukraine"]

    /// The built-in list's group for a two-letter country code, or nil when it has none.
    static func groupForCountry(_ code: String?) -> String? {
        guard let code = code?.trimmingCharacters(in: .whitespaces).uppercased(), !code.isEmpty else { return nil }
        let tag = code == "GB" ? "UK" : code
        return guideTagByGroup.first { $0.value == tag }?.key ?? extraGroupByCountry[code]
    }

    /// The guide file for a group among the many a worldwide playlist announces (named by country).
    static func guideUrl(forGroup group: String, in urls: [String]) -> String? {
        guard let tag = guideTagByGroup[group] else { return nil }
        return urls.first { url in
            let clean = url.components(separatedBy: "?").first ?? url
            return clean.range(of: "_\(tag)\\d\\.xml(\\.gz)?$", options: .regularExpression) != nil
        }
    }
}

// MARK: - M3U

enum M3uParser {
    static func isHttp(_ s: String) -> Bool {
        let l = s.lowercased()
        return l.hasPrefix("http://") || l.hasPrefix("https://")
    }

    static func looksLikePlaylist(_ text: String) -> Bool {
        let head = String(text.prefix(4096)).trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF} \n\r\t"))
        return head.lowercased().hasPrefix("#extm3u") || head.lowercased().contains("#extinf")
    }

    static func isSingleStreamManifest(_ text: String) -> Bool {
        let head = String(text.prefix(8192))
        return head.contains("#EXT-X-STREAM-INF") || head.contains("#EXT-X-TARGETDURATION") || head.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    private static func attributes(_ line: String) -> [String: String] {
        var result: [String: String] = [:]
        guard let regex = try? NSRegularExpression(pattern: #"([A-Za-z0-9_-]+)="([^"]*)""#) else { return result }
        let ns = line as NSString
        for m in regex.matches(in: line, range: NSRange(location: 0, length: ns.length)) {
            result[ns.substring(with: m.range(at: 1)).lowercased()] = ns.substring(with: m.range(at: 2))
        }
        return result
    }

    private static func displayName(_ line: String) -> String {
        var inQuotes = false
        for (offset, ch) in line.enumerated() {
            if ch == "\"" { inQuotes.toggle() }
            else if ch == "," && !inQuotes {
                return String(line.dropFirst(offset + 1)).trimmingCharacters(in: .whitespaces)
            }
        }
        return ""
    }

    private static func splitUrlHeaders(_ line: String) -> (String, [String: String]) {
        guard let bar = line.firstIndex(of: "|") else { return (line, [:]) }
        var headers: [String: String] = [:]
        for part in line[line.index(after: bar)...].split(separator: "&") {
            let kv = part.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2 else { continue }
            let key = kv[0].trimmingCharacters(in: .whitespaces)
            let value = kv[1].trimmingCharacters(in: .whitespaces)
            if key.isEmpty || value.isEmpty { continue }
            switch key.lowercased() {
            case "user-agent": headers["User-Agent"] = value
            case "referer", "referrer": headers["Referer"] = value
            case "origin": headers["Origin"] = value
            default: headers[key] = value
            }
        }
        return (String(line[..<bar]).trimmingCharacters(in: .whitespaces), headers)
    }

    private static func playable(_ s: String) -> Bool {
        let l = s.lowercased()
        return isHttp(l) || l.hasPrefix("rtmp://") || l.hasPrefix("rtsp://") || l.hasPrefix("udp://") || l.hasPrefix("rtp://")
    }

    static func parse(_ text: String) -> LivePlaylist {
        var channels: [LiveChannel] = []
        var guideUrls: [String] = []
        var seen = Set<String>()
        var pendingName: String?
        var pendingAttributes: [String: String] = [:]
        var pendingGroup: String?
        var pendingHeaders: [String: String] = [:]

        func reset() {
            pendingName = nil
            pendingAttributes = [:]
            pendingGroup = nil
            pendingHeaders = [:]
        }

        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}").union(.whitespaces))
            let lower = line.lowercased()
            if line.isEmpty { continue }
            if lower.hasPrefix("#extm3u") {
                let a = attributes(line)
                for key in ["url-tvg", "x-tvg-url", "tvg-url"] {
                    if let v = a[key] {
                        for u in v.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) where isHttp(u) && !guideUrls.contains(u) {
                            guideUrls.append(u)
                        }
                    }
                }
            } else if lower.hasPrefix("#extinf") {
                pendingAttributes = attributes(line)
                pendingName = displayName(line)
            } else if lower.hasPrefix("#extgrp:") {
                let g = line.split(separator: ":", maxSplits: 1).last.map { $0.trimmingCharacters(in: .whitespaces) } ?? ""
                pendingGroup = g.isEmpty ? nil : g
            } else if lower.hasPrefix("#extvlcopt:") {
                let option = String(line.dropFirst("#EXTVLCOPT:".count))
                let parts = option.split(separator: "=", maxSplits: 1).map(String.init)
                if parts.count == 2 {
                    let value = parts[1].trimmingCharacters(in: .whitespaces)
                    switch parts[0].trimmingCharacters(in: .whitespaces).lowercased() {
                    case "http-user-agent": pendingHeaders["User-Agent"] = value
                    case "http-referrer", "http-referer": pendingHeaders["Referer"] = value
                    case "http-origin": pendingHeaders["Origin"] = value
                    default: break
                    }
                }
            } else if line.hasPrefix("#") {
                continue
            } else {
                let (url, urlHeaders) = splitUrlHeaders(line)
                if playable(url) {
                    let a = pendingAttributes
                    var name = pendingName ?? ""
                    if name.trimmingCharacters(in: .whitespaces).isEmpty { name = a["tvg-name"] ?? "" }
                    if name.trimmingCharacters(in: .whitespaces).isEmpty {
                        name = (url.components(separatedBy: "?").first ?? url).components(separatedBy: "/").last ?? url
                    }
                    var id = url
                    var dup = 1
                    while !seen.insert(id).inserted { dup += 1; id = "\(url)#\(dup)" }
                    let group = (a["group-title"] ?? pendingGroup)?.trimmingCharacters(in: .whitespaces)
                    var headers = pendingHeaders
                    for (k, v) in urlHeaders { headers[k] = v }
                    channels.append(LiveChannel(
                        id: id, name: name, url: url,
                        logo: a["tvg-logo"].flatMap { isHttp($0) ? $0 : nil },
                        group: (group?.isEmpty ?? true) ? nil : group,
                        guideId: a["tvg-id"].map { $0.trimmingCharacters(in: .whitespaces) }.flatMap { $0.isEmpty ? nil : $0 },
                        headers: headers
                    ))
                }
                reset()
            }
        }
        return LivePlaylist(channels: channels, guideUrls: guideUrls)
    }
}

// MARK: - Xtream

enum XtreamApi {
    static func normalizeServer(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespaces)
        if value.isEmpty { return "" }
        if !value.contains("://") { value = "http://" + value }
        let parts = value.components(separatedBy: "://")
        guard parts.count >= 2 else { return "" }
        let authority = parts[1].components(separatedBy: "/")[0].components(separatedBy: "?")[0]
        return authority.isEmpty ? "" : "\(parts[0])://\(authority)"
    }

    /// Pulls the login out of a pasted Xtream playlist link (`http://host/get.php?username=U&password=P`).
    static func source(fromPlaylistLink link: String) -> LiveSource? {
        let trimmed = link.trimmingCharacters(in: .whitespaces)
        guard trimmed.lowercased().contains("get.php") || trimmed.lowercased().contains("player_api.php"),
              let comps = URLComponents(string: trimmed) else { return nil }
        let items = comps.queryItems ?? []
        let user = items.first { $0.name.lowercased() == "username" }?.value ?? ""
        let pass = items.first { $0.name.lowercased() == "password" }?.value ?? ""
        let server = normalizeServer(trimmed)
        if user.isEmpty || pass.isEmpty || server.isEmpty { return nil }
        return LiveSource(type: .xtream, url: server, username: user, password: pass)
    }

    private static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")) ?? s
    }

    static func loginUrl(_ s: LiveSource) -> String {
        "\(normalizeServer(s.url))/player_api.php?username=\(enc(s.username))&password=\(enc(s.password))"
    }
    static func apiUrl(_ s: LiveSource, _ action: String) -> String { loginUrl(s) + "&action=\(action)" }
    static func streamUrl(_ s: LiveSource, id: String) -> String {
        "\(normalizeServer(s.url))/live/\(enc(s.username))/\(enc(s.password))/\(id).m3u8"
    }
    static func shortGuideUrl(_ s: LiveSource, id: String) -> String {
        apiUrl(s, "get_short_epg") + "&stream_id=\(enc(id))&limit=4"
    }

    private static func string(_ obj: [String: Any], _ key: String) -> String? {
        switch obj[key] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }

    static func isAuthenticated(_ data: Data) -> Bool {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let user = root["user_info"] as? [String: Any] else { return false }
        return string(user, "auth") == "1"
    }

    static func parseCategories(_ data: Data) -> [String: String] {
        guard let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [:] }
        var result: [String: String] = [:]
        for item in array {
            if let id = string(item, "category_id"), let name = string(item, "category_name") { result[id] = name }
        }
        return result
    }

    static func parseStreams(_ data: Data, categories: [String: String], source: LiveSource) -> [LiveChannel] {
        guard let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { item in
            guard let sid = string(item, "stream_id"), let name = string(item, "name"),
                  !name.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            let icon = string(item, "stream_icon").flatMap { M3uParser.isHttp($0) ? $0 : nil }
            let epg = string(item, "epg_channel_id").flatMap { $0.isEmpty ? nil : $0 }
            return LiveChannel(id: "xtream:\(sid)", name: name, url: streamUrl(source, id: sid), logo: icon,
                               group: string(item, "category_id").flatMap { categories[$0] }, guideId: epg, xtreamStreamId: sid)
        }
    }

    static func parseShortGuide(_ data: Data) -> [LiveProgramme] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let listings = root["epg_listings"] as? [[String: Any]] else { return [] }
        return listings.compactMap { item in
            guard let s = string(item, "start_timestamp").flatMap(Double.init),
                  let e = string(item, "stop_timestamp").flatMap(Double.init) else { return nil }
            let raw = string(item, "title") ?? ""
            let title = Data(base64Encoded: raw).flatMap { String(data: $0, encoding: .utf8) } ?? raw
            let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty ? nil : LiveProgramme(title: clean, start: Date(timeIntervalSince1970: s), stop: Date(timeIntervalSince1970: e))
        }.sorted { $0.start < $1.start }
    }
}

// MARK: - XMLTV

enum XmltvParser {
    /// What is on now and next, by lower-case guide id. Only the wanted ids are kept.
    static func parseNowNext(_ xml: String, now: Date, wanted: Set<String>) -> [String: LiveNowNext] {
        var found: [String: LiveNowNext] = [:]
        guard let programme = try? NSRegularExpression(pattern: #"<programme\b([^>]*)>(.*?)</programme>"#, options: [.dotMatchesLineSeparators]),
              let attr = try? NSRegularExpression(pattern: #"([A-Za-z_-]+)\s*=\s*"([^"]*)""#),
              let titleRx = try? NSRegularExpression(pattern: #"<title\b[^>]*>(.*?)</title>"#, options: [.dotMatchesLineSeparators])
        else { return found }
        let ns = xml as NSString
        for m in programme.matches(in: xml, range: NSRange(location: 0, length: ns.length)) {
            let attrText = ns.substring(with: m.range(at: 1))
            var attrs: [String: String] = [:]
            let attrNS = attrText as NSString
            for a in attr.matches(in: attrText, range: NSRange(location: 0, length: attrNS.length)) {
                attrs[attrNS.substring(with: a.range(at: 1))] = attrNS.substring(with: a.range(at: 2))
            }
            guard let channel = attrs["channel"]?.trimmingCharacters(in: .whitespaces).lowercased(), !channel.isEmpty,
                  wanted.contains(channel),
                  let start = parseTime(attrs["start"]), let stop = parseTime(attrs["stop"]), stop > now else { continue }
            let body = ns.substring(with: m.range(at: 2)) as NSString
            guard let t = titleRx.firstMatch(in: body as String, range: NSRange(location: 0, length: body.length)) else { continue }
            let title = decode(body.substring(with: t.range(at: 1))).trimmingCharacters(in: .whitespacesAndNewlines)
            if title.isEmpty { continue }
            let p = LiveProgramme(title: title, start: start, stop: stop)
            var current = found[channel] ?? LiveNowNext()
            if start <= now {
                current.now = p
            } else if current.next == nil || start < current.next!.start {
                current.next = p
            }
            found[channel] = current
        }
        return found
    }

    /// XMLTV time: `YYYYMMDDhhmmss +hhmm`. Seconds and the offset are optional.
    static func parseTime(_ value: String?) -> Date? {
        guard let text = value?.trimmingCharacters(in: .whitespaces) else { return nil }
        let digits = String(text.prefix { $0.isNumber })
        guard digits.count >= 12 else { return nil }
        func part(_ a: Int, _ b: Int) -> Int { Int(digits.dropFirst(a).prefix(b - a)) ?? 0 }
        var comps = DateComponents()
        comps.year = part(0, 4); comps.month = part(4, 6); comps.day = part(6, 8)
        comps.hour = part(8, 10); comps.minute = part(10, 12)
        comps.second = digits.count >= 14 ? part(12, 14) : 0
        let offsetText = String(text.dropFirst(digits.count)).trimmingCharacters(in: .whitespaces)
        var offset = 0
        if offsetText.count >= 5, let sign = offsetText.first, sign == "+" || sign == "-" {
            let h = Int(offsetText.dropFirst().prefix(2)) ?? 0
            let m = Int(offsetText.dropFirst(3).prefix(2)) ?? 0
            offset = (h * 60 + m) * 60 * (sign == "-" ? -1 : 1)
        }
        comps.timeZone = TimeZone(secondsFromGMT: offset)
        return Calendar(identifier: .gregorian).date(from: comps)
    }

    private static func decode(_ text: String) -> String {
        text.replacingOccurrences(of: "<![CDATA[", with: "").replacingOccurrences(of: "]]>", with: "")
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&amp;", with: "&")
    }
}

// MARK: - Loading

enum LiveNetwork {
    static func data(_ urlString: String, timeout: TimeInterval = 30, headers: [String: String] = [:]) async -> Data? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        guard let (data, response) = try? await URLSession.shared.data(for: request) else { return nil }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
        return data
    }

    static func text(_ urlString: String) async -> String? {
        guard let data = await data(urlString) else { return nil }
        return String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
    }

    /// Reads a (possibly gzip-compressed) guide file as text, within a size limit.
    static func guideText(_ urlString: String) async -> String? {
        guard var data = await data(urlString, timeout: 60), data.count < 80_000_000 else { return nil }
        if data.count > 2, data[data.startIndex] == 0x1f, data[data.startIndex + 1] == 0x8b {
            guard let inflated = gunzip(data) else { return nil }
            data = inflated
        }
        return String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
    }

    static func gunzip(_ data: Data) -> Data? {
        let bytes = [UInt8](data)
        guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b, bytes[2] == 8 else { return nil }
        let flags = bytes[3]
        var offset = 10
        if flags & 0x04 != 0 {
            guard offset + 2 <= bytes.count else { return nil }
            offset += 2 + Int(bytes[offset]) + (Int(bytes[offset + 1]) << 8)
        }
        if flags & 0x08 != 0 { while offset < bytes.count, bytes[offset] != 0 { offset += 1 }; offset += 1 }
        if flags & 0x10 != 0 { while offset < bytes.count, bytes[offset] != 0 { offset += 1 }; offset += 1 }
        if flags & 0x02 != 0 { offset += 2 }
        guard offset < bytes.count - 8 else { return nil }
        let payload = Data(bytes[offset..<(bytes.count - 8)])
        return try? (payload as NSData).decompressed(using: .zlib) as Data
    }

    /// True or false when the stream answers, nil when that cannot be told. Only a missing stream or a
    /// failing server counts as down: other refusals (401, 403) often still play in the player.
    static func streamAnswers(_ channel: LiveChannel) async -> Bool? {
        guard M3uParser.isHttp(channel.url), let url = URL(string: channel.url) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 6
        for (k, v) in channel.headers { request.setValue(v, forHTTPHeaderField: k) }
        do {
            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            _ = bytes
            guard let http = response as? HTTPURLResponse else { return nil }
            let code = http.statusCode
            if (200...399).contains(code) { return true }
            if code == 404 || code == 410 || code >= 500 { return false }
            return nil
        } catch is URLError {
            return false
        } catch {
            return nil
        }
    }
}

// MARK: - Store

enum LiveTvSelection {
    static let all = ""
    static let favorites = "\u{0}favorites"
    static let recent = "\u{0}recent"
}

@MainActor
final class LiveTVStore: ObservableObject {
    @Published var channels: [LiveChannel] = []
    @Published var groups: [String] = []
    @Published var loading = false
    @Published var error: LiveTvError?
    @Published var usingDefault = false
    @Published var favorites: Set<String>
    @Published var recent: [String]
    @Published var guide: [String: LiveNowNext] = [:]
    @Published var offline: Set<String> = []

    private var playlist: LivePlaylist?
    private var source = LiveSource()
    private var loadTask: Task<Void, Never>?
    private var groupGuideRequested = Set<String>()
    private var groupCheckRequested = Set<String>()
    private var channelGuideRequested = Set<String>()
    private var loadedKey = ""
    private static let maxRecent = 12

    private let store = ProfileScope.defaults

    init() {
        let d = store
        favorites = Set(d.stringArray(forKey: "liveFavorites") ?? [])
        recent = d.stringArray(forKey: "liveRecent") ?? []
    }

    /// Loads the channel list for [source] (an empty address means the built-in free channels).
    func load(source newSource: LiveSource, force: Bool = false) {
        let key = "\(newSource.type.rawValue)|\(newSource.url)|\(newSource.username)|\(newSource.password)|\(newSource.guideUrl)"
        if !force, key == loadedKey, !channels.isEmpty { return }
        loadedKey = key
        loadTask?.cancel()
        source = newSource
        let isDefault = newSource.url.trimmingCharacters(in: .whitespaces).isEmpty
        usingDefault = isDefault
        let effective = isDefault ? LiveSource(type: .m3u, url: LiveTvDefaults.playlistURL) : newSource
        groupGuideRequested.removeAll()
        groupCheckRequested.removeAll()
        channelGuideRequested.removeAll()
        playlist = nil
        offline = []
        guide = [:]
        loading = true
        error = nil
        loadTask = Task {
            do {
                let list = try await Self.loadPlaylist(effective)
                if Task.isCancelled { return }
                playlist = list
                channels = list.channels
                var seen = Set<String>()
                groups = list.channels.compactMap { $0.group }.filter { !$0.isEmpty && seen.insert($0).inserted }
                loading = false
                let found = await Self.loadGuide(effective, playlist: list)
                if !Task.isCancelled, !found.isEmpty { guide.merge(found) { a, _ in a } }
            } catch let e as LiveError {
                if Task.isCancelled { return }
                channels = []; groups = []; loading = false; error = e.error
            } catch {
                if Task.isCancelled { return }
                channels = []; groups = []; loading = false; self.error = .network
            }
        }
    }

    struct LiveError: Error { var error: LiveTvError }

    private static func loadPlaylist(_ s: LiveSource) async throws -> LivePlaylist {
        switch s.type {
        case .m3u:
            let url = s.url.trimmingCharacters(in: .whitespaces)
            guard M3uParser.isHttp(url) else { throw LiveError(error: .invalidSource) }
            guard let body = await LiveNetwork.text(url) else { throw LiveError(error: .network) }
            guard M3uParser.looksLikePlaylist(body) else { throw LiveError(error: .notAPlaylist) }
            if M3uParser.isSingleStreamManifest(body) {
                let name = (url.components(separatedBy: "?")[0] as NSString).lastPathComponent
                return LivePlaylist(channels: [LiveChannel(id: url, name: name.isEmpty ? url : name, url: url)])
            }
            let list = M3uParser.parse(body)
            if list.channels.isEmpty { throw LiveError(error: .noChannels) }
            return list
        case .xtream:
            if XtreamApi.normalizeServer(s.url).isEmpty || s.username.isEmpty || s.password.isEmpty {
                throw LiveError(error: .invalidSource)
            }
            guard let login = await LiveNetwork.data(XtreamApi.loginUrl(s)) else { throw LiveError(error: .network) }
            guard XtreamApi.isAuthenticated(login) else { throw LiveError(error: .loginFailed) }
            let categories = await LiveNetwork.data(XtreamApi.apiUrl(s, "get_live_categories")).map(XtreamApi.parseCategories) ?? [:]
            guard let streams = await LiveNetwork.data(XtreamApi.apiUrl(s, "get_live_streams")) else { throw LiveError(error: .network) }
            let channels = XtreamApi.parseStreams(streams, categories: categories, source: s)
            if channels.isEmpty { throw LiveError(error: .noChannels) }
            return LivePlaylist(channels: channels)
        }
    }

    private static let maxAnnouncedGuideFiles = 6

    private static func hasGuidePerGroup(_ s: LiveSource, _ list: LivePlaylist) -> Bool {
        !M3uParser.isHttp(s.guideUrl.trimmingCharacters(in: .whitespaces)) && list.guideUrls.count > maxAnnouncedGuideFiles
    }

    private static func readGuides(_ urls: [String], channels: [LiveChannel]) async -> [String: LiveNowNext] {
        let wanted = Set(channels.compactMap { $0.guideId?.lowercased() })
        if urls.isEmpty || wanted.isEmpty { return [:] }
        var byGuide: [String: LiveNowNext] = [:]
        for url in urls {
            guard let text = await LiveNetwork.guideText(url) else { continue }
            let parsed = await Task.detached { XmltvParser.parseNowNext(text, now: Date(), wanted: wanted) }.value
            for (k, v) in parsed where byGuide[k] == nil { byGuide[k] = v }
        }
        var result: [String: LiveNowNext] = [:]
        for channel in channels {
            if let gid = channel.guideId?.lowercased(), let entry = byGuide[gid] { result[channel.id] = entry }
        }
        return result
    }

    private static func loadGuide(_ s: LiveSource, playlist: LivePlaylist) async -> [String: LiveNowNext] {
        if hasGuidePerGroup(s, playlist) { return [:] }
        let own = s.guideUrl.trimmingCharacters(in: .whitespaces)
        let urls = M3uParser.isHttp(own) ? [own] : playlist.guideUrls
        return await readGuides(Array(urls.prefix(2)), channels: playlist.channels)
    }

    /// Loads the guide for one group of a worldwide playlist the first time that group is opened.
    func requestGroupGuide(_ group: String) {
        guard let list = playlist else { return }
        let effective = usingDefault ? LiveSource(type: .m3u, url: LiveTvDefaults.playlistURL) : source
        guard Self.hasGuidePerGroup(effective, list), groupGuideRequested.insert(group).inserted,
              let url = LiveTvDefaults.guideUrl(forGroup: group, in: list.guideUrls) else { return }
        let members = list.channels.filter { $0.group == group }
        Task {
            let found = await Self.readGuides([url], channels: members)
            if !found.isEmpty { guide.merge(found) { a, _ in a } }
        }
    }

    /// Checks which built-in channels of a group answer, so the silent ones are listed last.
    /// Only the built-in list is checked: a provider's own list may allow a single connection.
    func requestGroupCheck(_ group: String) {
        guard usingDefault, let list = playlist, groupCheckRequested.insert(group).inserted else { return }
        let members = Array(list.channels.filter { $0.group == group }.prefix(200))
        if members.isEmpty { return }
        Task {
            await withTaskGroup(of: (String, Bool?).self) { tg in
                var iterator = members.makeIterator()
                var running = 0
                func addNext() {
                    guard let channel = iterator.next() else { return }
                    running += 1
                    tg.addTask { (channel.id, await LiveNetwork.streamAnswers(channel)) }
                }
                for _ in 0..<6 { addNext() }
                while let (id, answered) = await tg.next() {
                    running -= 1
                    if answered == false { offline.insert(id) }
                    addNext()
                }
            }
        }
    }

    /// Asks an Xtream provider what is on a channel now, once per channel.
    func requestChannelGuide(_ channel: LiveChannel) {
        guard !usingDefault, source.type == .xtream, let sid = channel.xtreamStreamId,
              guide[channel.id] == nil, channelGuideRequested.insert(channel.id).inserted else { return }
        let s = source
        Task {
            guard let data = await LiveNetwork.data(XtreamApi.shortGuideUrl(s, id: sid)) else { return }
            let now = Date()
            let upcoming = XtreamApi.parseShortGuide(data).filter { $0.stop > now }
            if upcoming.isEmpty { return }
            guide[channel.id] = LiveNowNext(now: upcoming.first { $0.start <= now }, next: upcoming.first { $0.start > now })
        }
    }

    func toggleFavorite(_ channel: LiveChannel) {
        if favorites.contains(channel.id) { favorites.remove(channel.id) } else { favorites.insert(channel.id) }
        store.set(Array(favorites), forKey: "liveFavorites")
    }

    func markWatched(_ channel: LiveChannel) {
        recent = Array(([channel.id] + recent.filter { $0 != channel.id }).prefix(Self.maxRecent))
        store.set(recent, forKey: "liveRecent")
    }

    /// The group to open first on the built-in channels: the one for the device's country.
    func preferredGroup() -> String? {
        guard usingDefault else { return nil }
        let code = Locale.current.region?.identifier
        guard let group = LiveTvDefaults.groupForCountry(code), groups.contains(group) else { return nil }
        return group
    }

    func visible(query: String, selection: String) -> [LiveChannel] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        func matches(_ c: LiveChannel) -> Bool { needle.isEmpty || c.name.range(of: needle, options: .caseInsensitive) != nil }
        var result: [LiveChannel]
        switch selection {
        case LiveTvSelection.favorites:
            result = channels.filter { favorites.contains($0.id) && matches($0) }
        case LiveTvSelection.recent:
            let byId = Dictionary(channels.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            result = recent.compactMap { byId[$0] }.filter(matches)
        case LiveTvSelection.all:
            result = channels.filter(matches)
        default:
            result = channels.filter { $0.group == selection && matches($0) }
        }
        if offline.isEmpty { return result }
        // A stable partition keeps the playlist order within working and silent channels.
        return result.filter { !offline.contains($0.id) } + result.filter { offline.contains($0.id) }
    }
}

// MARK: - Views

struct LiveTVView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var live: LiveTVStore
    @State private var selection = LiveTvSelection.all
    @State private var query = ""
    @State private var playing: PlayRequest?
    @State private var openedPreferred = false

    private var shown: [LiveChannel] { live.visible(query: query, selection: selection) }

    private func title(for selection: String) -> String {
        switch selection {
        case LiveTvSelection.all: return "All"
        case LiveTvSelection.favorites: return "Favorites"
        case LiveTvSelection.recent: return "Recent"
        default: return selection
        }
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text("Live TV").font(.largeTitle.bold()).padding(.leading, 60)
                if live.usingDefault {
                    Text("Free channels from the Free-TV project. Add your own playlist or login in Settings.")
                        .font(.callout).foregroundStyle(.secondary).padding(.leading, 60)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach([LiveTvSelection.all, LiveTvSelection.favorites, LiveTvSelection.recent] + live.groups, id: \.self) { g in
                            Button(title(for: g)) { selection = g }
                                .opacity(selection == g ? 1 : 0.7)
                        }
                    }
                    .padding(.horizontal, 60).padding(.vertical, 10)
                }
                if live.loading { ProgressView().padding(60) }
                if let error = live.error {
                    Text(error.rawValue).foregroundStyle(.orange).padding(.horizontal, 60)
                }
                ScrollView {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 30), count: 4), spacing: 30) {
                        ForEach(shown) { ch in
                            Button { play(ch) } label: { ChannelCard(channel: ch, info: live.guide[ch.id], offline: live.offline.contains(ch.id), favorite: live.favorites.contains(ch.id)) }
                                .buttonStyle(.card)
                                .contextMenu {
                                    Button(live.favorites.contains(ch.id) ? "Remove from favorites" : "Add to favorites") { live.toggleFavorite(ch) }
                                }
                                .onAppear { live.requestChannelGuide(ch) }
                        }
                    }
                    .padding(.horizontal, 60)
                }
            }
        }
        .searchable(text: $query)
        .task(id: settings.liveSource) {
            live.load(source: settings.liveSource)
        }
        .onChange(of: live.groups) { _, groups in
            if !openedPreferred, !groups.isEmpty, let g = live.preferredGroup() {
                openedPreferred = true
                selection = g
            }
        }
        .onChange(of: selection) { _, value in
            if !value.isEmpty, !value.hasPrefix("\u{0}") {
                live.requestGroupGuide(value)
                live.requestGroupCheck(value)
            }
        }
        .fullScreenCover(item: $playing) { req in
            PlayerScreen(request: req, settings: settings)
        }
    }

    private func play(_ ch: LiveChannel) {
        live.markWatched(ch)
        let item = StreamItem(name: ch.name, title: nil, description: nil, url: ch.url, infoHash: nil, behaviorHints: nil)
        playing = PlayRequest(meta: MetaItem(id: ch.id, type: "tv", name: ch.name), videoId: nil, title: ch.name,
                              candidates: [item], headers: ch.headers, isLive: true)
    }
}

struct ChannelCard: View {
    let channel: LiveChannel
    let info: LiveNowNext?
    let offline: Bool
    let favorite: Bool

    var body: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topTrailing) {
                AsyncImage(url: channel.logo.flatMap(URL.init(string:))) { phase in
                    if case .success(let img) = phase { img.resizable().scaledToFit() }
                    else { Image(systemName: "tv").font(.largeTitle) }
                }
                .frame(height: 90)
                .frame(maxWidth: .infinity)
                if favorite { Image(systemName: "star.fill").foregroundStyle(.yellow) }
            }
            Text(channel.name).lineLimit(1).font(.headline)
            if let now = info?.now?.title {
                Text(now).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                if let next = info?.next?.title {
                    Text("Next: \(next)").font(.caption2).lineLimit(1).foregroundStyle(.secondary)
                }
            } else if offline {
                Text("Not responding").font(.caption).foregroundStyle(.orange)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .opacity(offline ? 0.55 : 1)
    }
}
