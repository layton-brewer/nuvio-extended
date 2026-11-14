import Foundation

/// Checks the Meowio releases for a newer version, like the in-app updater on Android.
/// Apple TV apps are sideloaded, so this only tells the user an update exists.
@MainActor
final class UpdateChecker: ObservableObject {
    enum State: Equatable {
        case idle, checking, upToDate, available(String), failed
    }

    @Published private(set) var state: State = .idle

    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    func check() async {
        state = .checking
        guard let url = URL(string: "https://api.github.com/repos/layton-brewer/Meowio/releases/latest") else {
            state = .failed
            return
        }
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Meowio", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 15
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tag = json["tag_name"] as? String else {
                state = .failed
                return
            }
            state = Self.isNewer(tag, than: Self.currentVersion) ? .available(tag) : .upToDate
        } catch {
            state = .failed
        }
    }

    /// Compares dotted version numbers, ignoring a leading "v" and any "-suffix".
    static func isNewer(_ remote: String, than local: String) -> Bool {
        func parts(_ v: String) -> [Int] {
            let trimmed = v.hasPrefix("v") ? String(v.dropFirst()) : v
            let core = trimmed.split(separator: "-").first.map(String.init) ?? trimmed
            return core.split(separator: ".").map { Int($0) ?? 0 }
        }
        let r = parts(remote), l = parts(local)
        for i in 0..<max(r.count, l.count) {
            let a = i < r.count ? r[i] : 0, b = i < l.count ? l[i] : 0
            if a != b { return a > b }
        }
        return false
    }
}
