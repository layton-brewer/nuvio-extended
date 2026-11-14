import SwiftUI

/// Trakt for one profile: device code sign-in (enter a code on trakt.tv/activate, like the Android TV app),
/// scrobbling while watching, watched history, playback progress and the watchlist.
@MainActor
final class TraktStore: ObservableObject {
    struct DeviceCode: Equatable {
        var deviceCode: String
        var userCode: String
        var verificationURL: String
        var expires: Date
        var interval: Int
    }

    @Published private(set) var username: String?
    @Published private(set) var pending: DeviceCode?
    @Published private(set) var watchlist: [MetaItem] = []
    @Published private(set) var syncing = false
    @Published var message: String?

    private let store = ProfileScope.defaults
    private var accessToken: String?
    private var refreshToken: String?
    private var expiresAt: Date?
    private var pollTask: Task<Void, Never>?
    private var lastScrobble: (key: String, action: String, at: Date)?

    static let api = "https://api.trakt.tv"

    init() {
        accessToken = store.string(forKey: "traktAccess")
        refreshToken = store.string(forKey: "traktRefresh")
        if let t = store.object(forKey: "traktExpires") as? Double { expiresAt = Date(timeIntervalSince1970: t) }
        username = store.string(forKey: "traktUser")
    }

    // MARK: App credentials

    /// The Trakt app this build uses: one baked in at build time, or one the user entered in Settings.
    static var clientId: String {
        let own = UserDefaults.standard.string(forKey: "traktClientId")?.trimmingCharacters(in: .whitespaces) ?? ""
        return own.isEmpty ? BuildSecrets.traktClientId : own
    }
    static var clientSecret: String {
        let own = UserDefaults.standard.string(forKey: "traktClientSecret")?.trimmingCharacters(in: .whitespaces) ?? ""
        return own.isEmpty ? BuildSecrets.traktClientSecret : own
    }
    static var hasBuiltInApp: Bool { !BuildSecrets.traktClientId.isEmpty && !BuildSecrets.traktClientSecret.isEmpty }
    static var configured: Bool { !clientId.isEmpty && !clientSecret.isEmpty }

    var connected: Bool { accessToken != nil }

    // MARK: Sign-in

    func startSignIn() async {
        guard Self.configured else {
            message = "Add a Trakt app's client ID and secret first."
            return
        }
        message = nil
        guard let obj = await post("/oauth/device/code", ["client_id": Self.clientId], auth: false).json as? [String: Any],
              let device = obj["device_code"] as? String, let user = obj["user_code"] as? String else {
            message = "Could not reach Trakt. Check the client ID."
            return
        }
        let code = DeviceCode(deviceCode: device, userCode: user,
                              verificationURL: obj["verification_url"] as? String ?? "https://trakt.tv/activate",
                              expires: Date().addingTimeInterval(Double(obj["expires_in"] as? Int ?? 600)),
                              interval: max(obj["interval"] as? Int ?? 5, 2))
        pending = code
        pollTask?.cancel()
        pollTask = Task { await poll(code) }
    }

    func cancelSignIn() {
        pollTask?.cancel()
        pending = nil
    }

    private func poll(_ code: DeviceCode) async {
        var wait = code.interval
        while !Task.isCancelled, Date() < code.expires {
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000_000)
            if Task.isCancelled { return }
            let r = await post("/oauth/device/token", ["code": code.deviceCode, "client_id": Self.clientId,
                                                        "client_secret": Self.clientSecret], auth: false)
            switch r.status {
            case 200:
                if let obj = r.json as? [String: Any] { saveTokens(obj) }
                pending = nil
                await loadUser()
                await sync()
                return
            case 400: continue
            case 429: wait += 1
            case 404, 409, 410, 418:
                pending = nil
                message = r.status == 418 ? "Sign-in was declined on Trakt." : "The code expired. Try again."
                return
            default: continue
            }
        }
        pending = nil
    }

    func signOut() {
        if let token = accessToken {
            let body = ["token": token, "client_id": Self.clientId, "client_secret": Self.clientSecret]
            Task { _ = await post("/oauth/revoke", body, auth: false) }
        }
        accessToken = nil
        refreshToken = nil
        expiresAt = nil
        username = nil
        watchlist = []
        for k in ["traktAccess", "traktRefresh", "traktExpires", "traktUser"] { store.removeObject(forKey: k) }
    }

    private func saveTokens(_ obj: [String: Any]) {
        accessToken = obj["access_token"] as? String
        refreshToken = obj["refresh_token"] as? String ?? refreshToken
        let created = obj["created_at"] as? Double ?? Date().timeIntervalSince1970
        expiresAt = Date(timeIntervalSince1970: created + (obj["expires_in"] as? Double ?? 7_776_000))
        store.set(accessToken, forKey: "traktAccess")
        store.set(refreshToken, forKey: "traktRefresh")
        store.set(expiresAt?.timeIntervalSince1970, forKey: "traktExpires")
    }

    private func refreshIfNeeded() async {
        guard let refreshToken, let expiresAt, expiresAt.timeIntervalSinceNow < 86_400 else { return }
        let r = await post("/oauth/token", ["refresh_token": refreshToken, "client_id": Self.clientId,
                                            "client_secret": Self.clientSecret, "grant_type": "refresh_token",
                                            "redirect_uri": "urn:ietf:wg:oauth:2.0:oob"], auth: false)
        if r.status == 200, let obj = r.json as? [String: Any] { saveTokens(obj) }
        else if r.status == 401 || r.status == 400 { signOut(); message = "Trakt signed out. Sign in again." }
    }

    private func loadUser() async {
        if let obj = await get("/users/settings").json as? [String: Any],
           let user = obj["user"] as? [String: Any] {
            username = (user["username"] as? String) ?? (user["name"] as? String)
            store.set(username, forKey: "traktUser")
        }
    }

    // MARK: HTTP

    private struct Reply { var status: Int; var json: Any? }

    private func request(_ path: String, method: String, body: Any?, auth: Bool) async -> Reply {
        guard let url = URL(string: Self.api + path) else { return Reply(status: 0, json: nil) }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.timeoutInterval = 20
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("2", forHTTPHeaderField: "trakt-api-version")
        r.setValue(Self.clientId, forHTTPHeaderField: "trakt-api-key")
        r.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        if auth, let accessToken { r.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization") }
        if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        guard let result = try? await URLSession.shared.data(for: r) else { return Reply(status: 0, json: nil) }
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0
        return Reply(status: status, json: try? JSONSerialization.jsonObject(with: result.0))
    }

    private func post(_ path: String, _ body: Any, auth: Bool = true) async -> Reply {
        await request(path, method: "POST", body: body, auth: auth)
    }

    private func get(_ path: String) async -> Reply {
        await request(path, method: "GET", body: nil, auth: true)
    }

    // MARK: Scrobbling

    /// Body that names a movie or an episode by IMDb id, or nil for titles Trakt cannot match.
    private static func item(meta: MetaItem, videoId: String?) -> [String: Any]? {
        if meta.type == "movie", meta.id.hasPrefix("tt") {
            return ["movie": ["ids": ["imdb": meta.id]]]
        }
        if meta.type == "series", let ref = EpisodeRef(videoId), ref.showId.hasPrefix("tt") {
            return ["show": ["ids": ["imdb": ref.showId]], "episode": ["season": ref.season, "number": ref.episode]]
        }
        return nil
    }

    /// Tells Trakt what is playing. Action is start, pause or stop; Trakt marks it watched when stopped past 80%.
    func scrobble(_ action: String, meta: MetaItem, videoId: String?, progress: Double) {
        guard connected, var body = Self.item(meta: meta, videoId: videoId) else { return }
        let key = videoId ?? meta.id
        if let last = lastScrobble, last.key == key, last.action == action, Date().timeIntervalSince(last.at) < 5 { return }
        lastScrobble = (key, action, Date())
        body["progress"] = (progress * 100).rounded() / 100
        Task {
            await refreshIfNeeded()
            _ = await post("/scrobble/\(action)", body)
        }
    }

    /// Adds or removes a watched mark (the episode menu's Mark as watched).
    func setWatched(_ watched: Bool, meta: MetaItem, videoId: String?) {
        guard connected, let item = Self.item(meta: meta, videoId: videoId) else { return }
        var body: [String: Any] = [:]
        if let movie = item["movie"] as? [String: Any] {
            var m = movie
            if watched { m["watched_at"] = ISO8601DateFormatter().string(from: Date()) }
            body["movies"] = [m] as [[String: Any]]
        } else if let show = item["show"] as? [String: Any], let ep = item["episode"] as? [String: Any] {
            var s = show
            let episodes: [[String: Any]] = [["number": ep["number"] ?? 0]]
            let seasons: [[String: Any]] = [["number": ep["season"] ?? 0, "episodes": episodes]]
            s["seasons"] = seasons
            body["shows"] = [s] as [[String: Any]]
        }
        Task {
            await refreshIfNeeded()
            _ = await post(watched ? "/sync/history" : "/sync/history/remove", body)
        }
    }

    func setInWatchlist(_ add: Bool, meta: MetaItem) {
        guard connected, meta.id.hasPrefix("tt"), meta.type == "movie" || meta.type == "series" else { return }
        let entry: [String: Any] = ["ids": ["imdb": meta.id]]
        let body: [String: Any] = meta.type == "movie" ? ["movies": [entry]] : ["shows": [entry]]
        if add {
            if !watchlist.contains(where: { $0.id == meta.id }) { watchlist.insert(meta, at: 0) }
        } else {
            watchlist.removeAll { $0.id == meta.id }
        }
        Task {
            await refreshIfNeeded()
            _ = await post(add ? "/sync/watchlist" : "/sync/watchlist/remove", body)
        }
    }

    // MARK: Sync

    private static func poster(_ imdb: String) -> String { "https://images.metahub.space/poster/medium/\(imdb)/img" }
    private static func background(_ imdb: String) -> String { "https://images.metahub.space/background/medium/\(imdb)/img" }

    private static func meta(from obj: [String: Any], type: String) -> MetaItem? {
        guard let ids = obj["ids"] as? [String: Any], let imdb = ids["imdb"] as? String, imdb.hasPrefix("tt") else { return nil }
        var m = MetaItem(id: imdb, type: type, name: obj["title"] as? String ?? imdb)
        m.poster = poster(imdb)
        m.background = background(imdb)
        if let year = obj["year"] as? Int { m.releaseInfo = String(year) }
        return m
    }

    private static func date(_ s: Any?) -> Date {
        guard let s = s as? String else { return Date() }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: s) ?? ISO8601DateFormatter().date(from: s) ?? Date()
    }

    /// Pulls the watchlist, watched history and unfinished playback into this profile.
    func sync(into library: LibraryStore? = nil) async {
        guard connected, !syncing else { return }
        syncing = true
        defer { syncing = false }
        await refreshIfNeeded()
        if username == nil { await loadUser() }

        var list: [MetaItem] = []
        if let items = await get("/sync/watchlist?sort_by=added&sort_how=desc").json as? [[String: Any]] {
            for it in items {
                if let m = it["movie"] as? [String: Any], let meta = Self.meta(from: m, type: "movie") { list.append(meta) }
                if let s = it["show"] as? [String: Any], let meta = Self.meta(from: s, type: "series") { list.append(meta) }
            }
        }
        watchlist = list
        guard let library else { return }
        library.mergeSaved(list)

        if let movies = await get("/sync/watched/movies").json as? [[String: Any]] {
            for it in movies {
                guard let m = it["movie"] as? [String: Any], let meta = Self.meta(from: m, type: "movie") else { continue }
                library.markWatched(meta: meta, videoId: nil, notify: false, at: Self.date(it["last_watched_at"]))
            }
        }
        if let shows = await get("/sync/watched/shows").json as? [[String: Any]] {
            for it in shows.prefix(300) {
                guard let s = it["show"] as? [String: Any], let meta = Self.meta(from: s, type: "series") else { continue }
                for season in it["seasons"] as? [[String: Any]] ?? [] {
                    guard let sn = season["number"] as? Int, sn > 0 else { continue }
                    for ep in season["episodes"] as? [[String: Any]] ?? [] {
                        guard let en = ep["number"] as? Int else { continue }
                        library.markWatched(meta: meta, videoId: "\(meta.id):\(sn):\(en)", notify: false,
                                            at: Self.date(ep["last_watched_at"]))
                    }
                }
            }
        }
        if let playback = await get("/sync/playback?limit=30").json as? [[String: Any]] {
            for it in playback {
                let pct = (it["progress"] as? Double ?? 0) / 100
                let at = Self.date(it["paused_at"])
                if let m = it["movie"] as? [String: Any], let meta = Self.meta(from: m, type: "movie") {
                    let runtime = Double(m["runtime"] as? Int ?? 0) * 60
                    let duration = runtime > 0 ? runtime : 6000
                    library.mergeProgress(itemId: meta.id, meta: meta, isEpisode: false, seconds: pct * duration,
                                          duration: duration, completed: false, at: at)
                } else if let s = it["show"] as? [String: Any], let e = it["episode"] as? [String: Any],
                          let meta = Self.meta(from: s, type: "series"),
                          let sn = e["season"] as? Int, let en = e["number"] as? Int {
                    let runtime = Double(e["runtime"] as? Int ?? 0) * 60
                    let duration = runtime > 0 ? runtime : 2700
                    library.mergeProgress(itemId: "\(meta.id):\(sn):\(en)", meta: meta, isEpisode: true,
                                          seconds: pct * duration, duration: duration, completed: false, at: at)
                }
            }
        }
    }
}

// MARK: - Settings page

struct TraktSettingsView: View {
    @EnvironmentObject var trakt: TraktStore
    @EnvironmentObject var library: LibraryStore
    @AppStorage("traktClientId") private var clientId = ""
    @AppStorage("traktClientSecret") private var clientSecret = ""

    var body: some View {
        List {
            if trakt.connected {
                Section {
                    Text("Signed in as \(trakt.username ?? "Trakt user")")
                    Button(trakt.syncing ? "Syncing…" : "Sync now") { Task { await trakt.sync(into: library) } }
                        .disabled(trakt.syncing)
                    Button("Sign out", role: .destructive) { trakt.signOut() }
                } footer: {
                    Text("What you watch is scrobbled to Trakt, your watched history and unfinished titles come back here, and My List follows your Trakt watchlist.")
                }
            } else if let code = trakt.pending {
                Section {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("On your phone or computer, go to").foregroundStyle(.secondary)
                        Text(code.verificationURL).font(.title2.bold())
                        Text("and enter this code:").foregroundStyle(.secondary)
                        Text(code.userCode).font(.system(size: 72, weight: .heavy, design: .monospaced)).kerning(8)
                        HStack(spacing: 14) { ProgressView(); Text("Waiting for Trakt…") }
                    }
                    .padding(.vertical, 20)
                    Button("Cancel") { trakt.cancelSignIn() }
                }
            } else {
                Section {
                    Button("Sign in to Trakt") { Task { await trakt.startSignIn() } }
                        .disabled(!TraktStore.configured)
                } footer: {
                    Text(TraktStore.configured ? "You'll get a short code to enter at trakt.tv/activate."
                         : "This build has no Trakt app built in. Create one at trakt.tv/oauth/applications (redirect URI urn:ietf:wg:oauth:2.0:oob) and paste its client ID and secret below.")
                }
            }
            if let m = trakt.message { Text(m).foregroundStyle(.orange) }
            Section(TraktStore.hasBuiltInApp ? "Your own Trakt app (optional)" : "Trakt app") {
                TextField("Client ID", text: $clientId)
                SecureField("Client secret", text: $clientSecret)
            }
        }
        .navigationTitle("Trakt")
    }
}
