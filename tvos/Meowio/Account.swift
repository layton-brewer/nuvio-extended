import SwiftUI

/// Meowio account (the same Supabase account server the Android apps use when it is set up). Signing in
/// brings the account's profiles, add-ons, My List, watch progress and watched marks to this Apple TV and keeps
/// sending changes back. Off when the build has no account server.
@MainActor
final class AccountStore: ObservableObject {
    @Published private(set) var email: String?
    @Published private(set) var busy = false
    @Published private(set) var lastSync: Date?
    @Published var message: String?

    private var accessToken: String?
    private var refreshToken: String?
    private var expiresAt: Date?
    private var userId: String?
    private var ownerId: String?
    /// Identifies this device in sync calls so the server can skip echoing its own changes back.
    private let clientId: String

    static var serverURL: String {
        var s = BuildSecrets.supabaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }
    static var anonKey: String { BuildSecrets.supabaseAnonKey.trimmingCharacters(in: .whitespacesAndNewlines) }
    static var available: Bool { !serverURL.isEmpty && !anonKey.isEmpty }

    var signedIn: Bool { accessToken != nil }

    init() {
        let d = UserDefaults.standard
        accessToken = d.string(forKey: "accountAccess")
        refreshToken = d.string(forKey: "accountRefresh")
        if let t = d.object(forKey: "accountExpires") as? Double { expiresAt = Date(timeIntervalSince1970: t) }
        userId = d.string(forKey: "accountUser")
        email = d.string(forKey: "accountEmail")
        if let id = d.string(forKey: "syncClientId") {
            clientId = id
        } else {
            clientId = UUID().uuidString.lowercased()
            d.set(clientId, forKey: "syncClientId")
        }
    }

    // MARK: Sign-in

    func signIn(email: String, password: String, create: Bool) async {
        guard Self.available else { return }
        busy = true
        message = nil
        defer { busy = false }
        let path = create ? "/auth/v1/signup" : "/auth/v1/token?grant_type=password"
        let r = await call(path, body: ["email": email.trimmingCharacters(in: .whitespaces), "password": password], authed: false)
        guard let obj = r.json as? [String: Any] else {
            message = "Could not reach the account server."
            return
        }
        if obj["access_token"] != nil {
            save(obj)
            self.email = email.trimmingCharacters(in: .whitespaces)
            UserDefaults.standard.set(self.email, forKey: "accountEmail")
            message = nil
        } else if create, r.status < 300 {
            message = "Check your email to confirm the account, then sign in."
        } else {
            let text = (obj["error_description"] as? String) ?? (obj["msg"] as? String) ?? (obj["message"] as? String)
            message = text ?? "Sign-in failed. Check the email and password."
        }
    }

    func signOut() {
        if accessToken != nil { Task { _ = await call("/auth/v1/logout", body: [String: String]()) } }
        accessToken = nil
        refreshToken = nil
        expiresAt = nil
        userId = nil
        ownerId = nil
        email = nil
        for k in ["accountAccess", "accountRefresh", "accountExpires", "accountUser", "accountEmail"] {
            UserDefaults.standard.removeObject(forKey: k)
        }
    }

    private func save(_ obj: [String: Any]) {
        accessToken = obj["access_token"] as? String
        refreshToken = obj["refresh_token"] as? String ?? refreshToken
        expiresAt = Date().addingTimeInterval(obj["expires_in"] as? Double ?? 3600)
        if let user = obj["user"] as? [String: Any] {
            userId = user["id"] as? String ?? userId
            if let mail = user["email"] as? String { email = mail; UserDefaults.standard.set(mail, forKey: "accountEmail") }
        }
        let d = UserDefaults.standard
        d.set(accessToken, forKey: "accountAccess")
        d.set(refreshToken, forKey: "accountRefresh")
        d.set(expiresAt?.timeIntervalSince1970, forKey: "accountExpires")
        d.set(userId, forKey: "accountUser")
    }

    private func refresh() async -> Bool {
        guard let refreshToken else { return false }
        let r = await call("/auth/v1/token?grant_type=refresh_token", body: ["refresh_token": refreshToken], authed: false)
        if r.status == 200, let obj = r.json as? [String: Any], obj["access_token"] != nil {
            save(obj)
            return true
        }
        if r.status == 400 || r.status == 401 {
            signOut()
            message = "You were signed out. Sign in again to keep syncing."
        }
        return false
    }

    // MARK: HTTP

    struct Reply { var status: Int; var json: Any? }

    private func call(_ path: String, body: Any?, method: String = "POST", authed: Bool = true) async -> Reply {
        if authed, let expiresAt, expiresAt.timeIntervalSinceNow < 60 { _ = await refresh() }
        var reply = await send(path, body: body, method: method, authed: authed)
        if authed, reply.status == 401, await refresh() {
            reply = await send(path, body: body, method: method, authed: authed)
        }
        return reply
    }

    private func send(_ path: String, body: Any?, method: String, authed: Bool) async -> Reply {
        guard let url = URL(string: Self.serverURL + path) else { return Reply(status: 0, json: nil) }
        var r = URLRequest(url: url)
        r.httpMethod = body == nil ? "GET" : method
        r.timeoutInterval = 20
        r.setValue(Self.anonKey, forHTTPHeaderField: "apikey")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.setValue("Bearer \(authed ? (accessToken ?? Self.anonKey) : Self.anonKey)", forHTTPHeaderField: "Authorization")
        if let body { r.httpBody = try? JSONSerialization.data(withJSONObject: body) }
        guard let result = try? await URLSession.shared.data(for: r) else { return Reply(status: 0, json: nil) }
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0
        return Reply(status: status, json: try? JSONSerialization.jsonObject(with: result.0, options: [.fragmentsAllowed]))
    }

    private func rpc(_ name: String, _ params: [String: Any] = [:]) async -> Reply {
        await call("/rest/v1/rpc/\(name)", body: params)
    }

    // MARK: Sync

    private func owner() async -> String? {
        if let ownerId { return ownerId }
        let r = await rpc("get_sync_owner")
        if r.status < 300 {
            if let s = r.json as? String { ownerId = s }
            else if let rows = r.json as? [[String: Any]], let s = rows.first?.values.first as? String { ownerId = s }
            else if let obj = r.json as? [String: Any], let s = obj.values.first as? String { ownerId = s }
        }
        return ownerId ?? userId
    }

    /// The account's profiles, for the "Who's watching?" screen.
    func pullProfiles(into profiles: ProfileStore) async {
        guard signedIn else { return }
        let r = await rpc("sync_pull_profiles")
        guard let rows = r.json as? [[String: Any]] else { return }
        let remote = rows.compactMap { row -> Profile? in
            guard let index = row["profile_index"] as? Int else { return nil }
            let name = (row["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (index == 1 ? "Main" : "Profile \(index)")
            return Profile(id: index, name: name, colorHex: row["avatar_color_hex"] as? String ?? "#1E88E5",
                           usesPrimaryAddons: row["uses_primary_addons"] as? Bool ?? false)
        }
        profiles.adopt(remote: remote)
    }

    /// Profiles made on this Apple TV go up to the account so other devices see them.
    func pushProfiles(_ profiles: [Profile]) async {
        guard signedIn else { return }
        let rows: [[String: Any]] = profiles.map {
            ["profile_index": $0.id, "name": $0.name, "avatar_color_hex": $0.colorHex, "uses_primary_addons": $0.usesPrimaryAddons]
        }
        _ = await rpc("sync_push_profiles", ["p_profiles": rows])
    }

    /// Full two-way sync for one profile: add-ons, My List, progress and watched marks.
    func sync(profile: Profile, settings: SettingsStore, library: LibraryStore) async {
        guard signedIn, !busy else { return }
        busy = true
        defer { busy = false; lastSync = Date() }
        await syncAddons(profile: profile, settings: settings)
        await syncLibrary(profileId: profile.id, library: library)
        await syncProgress(profileId: profile.id, library: library)
        await syncWatched(profileId: profile.id, library: library)
    }

    private func syncAddons(profile: Profile, settings: SettingsStore) async {
        let addonProfile = profile.usesPrimaryAddons ? 1 : profile.id
        guard let owner = await owner() else { return }
        let r = await call("/rest/v1/addons?select=url,sort_order,enabled&user_id=eq.\(owner)&profile_id=eq.\(addonProfile)&order=sort_order.asc",
                           body: nil, method: "GET")
        let rows = (r.json as? [[String: Any]]) ?? []
        let remote = rows.filter { ($0["enabled"] as? Bool) ?? true }.compactMap { $0["url"] as? String }
        if r.status < 300, !remote.isEmpty {
            var cleaned: [String] = []
            for raw in remote {
                var s = raw.trimmingCharacters(in: .whitespaces)
                if s.hasSuffix("/manifest.json") { s = String(s.dropLast("/manifest.json".count)) }
                while s.hasSuffix("/") { s.removeLast() }
                if !s.isEmpty, !cleaned.contains(s) { cleaned.append(s) }
            }
            if cleaned != settings.addons { settings.addons = cleaned }
        } else if r.status < 300, !settings.addons.isEmpty, !profile.usesPrimaryAddons {
            await pushAddons(settings.addons, profileId: profile.id)
        }
    }

    func pushAddons(_ addons: [String], profileId: Int) async {
        guard signedIn else { return }
        let list: [[String: Any]] = addons.enumerated().map { ["url": $0.element + "/manifest.json", "sort_order": $0.offset, "enabled": true] }
        _ = await rpc("sync_push_addons", ["p_addons": list, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private func syncLibrary(profileId: Int, library: LibraryStore) async {
        var remote: [MetaItem] = []
        var offset = 0
        while offset < 2000 {
            let r = await rpc("sync_pull_library", ["p_profile_id": profileId, "p_limit": 200, "p_offset": offset])
            guard let rows = r.json as? [[String: Any]] else { break }
            for row in rows {
                guard let id = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
                var m = MetaItem(id: id, type: type, name: row["name"] as? String ?? id)
                m.poster = row["poster"] as? String
                m.background = row["background"] as? String
                m.description = row["description"] as? String
                m.releaseInfo = row["release_info"] as? String
                if let rating = row["imdb_rating"] as? Double { m.imdbRating = String(format: "%.1f", rating) }
                m.genres = row["genres"] as? [String]
                m.addonBase = row["addon_base_url"] as? String
                remote.append(m)
            }
            if rows.count < 200 { break }
            offset += 200
        }
        let missing = library.saved.filter { s in !remote.contains { $0.id == s.id } }
        library.mergeSaved(remote)
        if !missing.isEmpty { await pushLibrary(missing, profileId: profileId) }
    }

    func pushLibrary(_ items: [MetaItem], profileId: Int) async {
        guard signedIn, !items.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let rows: [[String: Any]] = items.map { (m: MetaItem) -> [String: Any] in
            var row: [String: Any] = ["content_id": m.id, "content_type": m.type, "name": m.name, "poster_shape": "POSTER",
                                      "genres": m.genres ?? [], "added_at": now]
            row["poster"] = m.poster ?? NSNull()
            row["background"] = m.background ?? NSNull()
            row["description"] = m.description ?? NSNull()
            row["release_info"] = m.releaseInfo ?? NSNull()
            row["imdb_rating"] = m.imdbRating.flatMap(Double.init) ?? NSNull()
            row["addon_base_url"] = m.addonBase ?? NSNull()
            return row
        }
        _ = await rpc("sync_push_library_items", ["p_items": rows, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    func removeFromLibrary(_ item: MetaItem, profileId: Int) async {
        guard signedIn else { return }
        _ = await rpc("sync_delete_library_items", ["p_keys": [["content_id": item.id, "content_type": item.type]],
                                                    "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private static func progressKey(contentId: String, videoId: String?) -> String {
        if let ref = EpisodeRef(videoId) { return "\(contentId)_s\(ref.season)e\(ref.episode)" }
        return contentId
    }

    private func syncProgress(profileId: Int, library: LibraryStore) async {
        let r = await rpc("sync_pull_watch_progress", ["p_profile_id": profileId])
        guard let rows = r.json as? [[String: Any]] else { return }
        var needMeta = Set<String>()
        for row in rows {
            guard let contentId = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
            let videoId = row["video_id"] as? String
            let isEpisode = type == "series" && (row["season"] as? Int) != nil
            let itemId = isEpisode ? (videoId ?? contentId) : contentId
            let position = (row["position"] as? Double ?? 0) / 1000
            let duration = (row["duration"] as? Double ?? 0) / 1000
            let at = Date(timeIntervalSince1970: (row["last_watched"] as? Double ?? 0) / 1000)
            let known = library.continueWatching.first { $0.id == contentId }?.meta
                ?? library.saved.first { $0.id == contentId }
            let meta = known ?? MetaItem(id: contentId, type: type, name: contentId)
            if known == nil { needMeta.insert("\(type)|\(contentId)") }
            library.mergeProgress(itemId: itemId, meta: meta, isEpisode: isEpisode, seconds: position,
                                  duration: duration > 0 ? duration : nil,
                                  completed: duration > 0 && position / duration > 0.92, at: at)
        }
        // Names and artwork for titles this device has never opened.
        for key in needMeta.prefix(20) {
            let parts = key.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2, let m = await AddonClient.meta(type: parts[0], id: parts[1]) {
                library.refreshContinueMeta(m)
            }
        }
        // Send up what this device has that the account does not.
        let remoteKeys = Set(rows.compactMap { $0["progress_key"] as? String })
        let local = library.records.suffix(200).filter { !$0.completed && $0.seconds > 30 }
        let missing = local.filter { !remoteKeys.contains(Self.progressKey(contentId: $0.contentId, videoId: $0.isEpisode ? $0.id : nil)) }
        if !missing.isEmpty { await pushProgress(Array(missing), profileId: profileId) }
    }

    func pushProgress(_ records: [WatchRecord], profileId: Int, metaType: String? = nil) async {
        guard signedIn, !records.isEmpty else { return }
        let entries: [[String: Any]] = records.map { (r: WatchRecord) -> [String: Any] in
            let ref = r.isEpisode ? EpisodeRef(r.id) : nil
            var e: [String: Any] = [
                "content_id": r.contentId,
                "content_type": metaType ?? (r.isEpisode ? "series" : "movie"),
                "video_id": r.id,
                "position": Int64(r.seconds * 1000),
                "duration": Int64((r.duration ?? 0) * 1000),
                "last_watched": Int64(r.at.timeIntervalSince1970 * 1000),
                "progress_key": Self.progressKey(contentId: r.contentId, videoId: r.isEpisode ? r.id : nil)
            ]
            if let ref { e["season"] = ref.season; e["episode"] = ref.episode }
            return e
        }
        _ = await rpc("sync_push_watch_progress", ["p_entries": entries, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    private func syncWatched(profileId: Int, library: LibraryStore) async {
        var page = 1
        var remoteKeys = Set<String>()
        while page <= 20 {
            let r = await rpc("sync_pull_watched_items", ["p_profile_id": profileId, "p_page": page, "p_page_size": 500])
            guard let rows = r.json as? [[String: Any]], !rows.isEmpty else { break }
            for row in rows {
                guard let contentId = row["content_id"] as? String, let type = row["content_type"] as? String else { continue }
                let season = row["season"] as? Int
                let episode = row["episode"] as? Int
                var meta = MetaItem(id: contentId, type: type, name: row["title"] as? String ?? contentId)
                if let known = library.saved.first(where: { $0.id == contentId }) { meta = known }
                let videoId = (season != nil && episode != nil) ? "\(contentId):\(season!):\(episode!)" : nil
                remoteKeys.insert(videoId ?? contentId)
                let at = Date(timeIntervalSince1970: (row["watched_at"] as? Double ?? 0) / 1000)
                library.markWatched(meta: meta, videoId: videoId, notify: false, at: at)
            }
            if rows.count < 500 { break }
            page += 1
        }
        let missing = library.records.filter { $0.completed && !remoteKeys.contains($0.id) }.suffix(300)
        if !missing.isEmpty { await pushWatched(Array(missing), profileId: profileId) }
    }

    func pushWatched(_ records: [WatchRecord], profileId: Int) async {
        guard signedIn, !records.isEmpty else { return }
        let items: [[String: Any]] = records.map { (r: WatchRecord) -> [String: Any] in
            let ref = r.isEpisode ? EpisodeRef(r.id) : nil
            return [
                "content_id": r.contentId,
                "content_type": r.isEpisode ? "series" : "movie",
                "title": r.title,
                "season": ref.map { $0.season as Any } ?? NSNull(),
                "episode": ref.map { $0.episode as Any } ?? NSNull(),
                "watched_at": Int64(r.at.timeIntervalSince1970 * 1000)
            ]
        }
        _ = await rpc("sync_push_watched_items", ["p_items": items, "p_profile_id": profileId, "p_origin_client_id": clientId])
    }

    func removeWatched(_ record: WatchRecord, profileId: Int) async {
        guard signedIn else { return }
        var key: [String: Any] = ["content_id": record.contentId]
        if record.isEpisode, let ref = EpisodeRef(record.id) { key["season"] = ref.season; key["episode"] = ref.episode }
        _ = await rpc("sync_delete_watched_items", ["p_keys": [key], "p_profile_id": profileId])
    }
}

// MARK: - Settings page

struct AccountSettingsView: View {
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        List {
            if !AccountStore.available {
                Section {
                    Text("Accounts are off in this build because no account server is set up. Everything still works on this Apple TV, and you can move settings with Backup and restore.")
                        .foregroundStyle(.secondary)
                }
            } else if account.signedIn {
                Section {
                    Text("Signed in as \(account.email ?? "your account")")
                    Button(account.busy ? "Syncing…" : "Sync now") {
                        Task {
                            await account.pullProfiles(into: profiles)
                            if let p = profiles.active { await account.sync(profile: p, settings: settings, library: library) }
                        }
                    }
                    .disabled(account.busy)
                    if let last = account.lastSync {
                        Text("Last synced \(last.formatted(date: .omitted, time: .shortened))").foregroundStyle(.secondary)
                    }
                    Button("Sign out", role: .destructive) { account.signOut() }
                } footer: {
                    Text("Profiles, add-ons, My List, watch progress and watched episodes sync with your other Meowio devices.")
                }
            } else {
                Section {
                    TextField("Email", text: $email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                    Button(account.busy ? "Signing in…" : "Sign in") {
                        Task { await signedIn(create: false) }
                    }
                    .disabled(account.busy || email.isEmpty || password.isEmpty)
                    Button("Create account") { Task { await signedIn(create: true) } }
                        .disabled(account.busy || email.isEmpty || password.count < 6)
                } footer: {
                    Text("Use the same account as Meowio on your phone or Android TV.")
                }
            }
            if let m = account.message { Text(m).foregroundStyle(.orange) }
        }
        .navigationTitle("Account")
    }

    private func signedIn(create: Bool) async {
        await account.signIn(email: email, password: password, create: create)
        guard account.signedIn else { return }
        password = ""
        await account.pullProfiles(into: profiles)
        if let p = profiles.active { await account.sync(profile: p, settings: settings, library: library) }
    }
}
