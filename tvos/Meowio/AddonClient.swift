import Foundation

enum AddonClient {
    static let cinemeta = "https://v3-cinemeta.strem.io"

    static func fetch<T: Decodable>(_ type: T.Type, from urlString: String) async -> T? {
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            return nil
        }
    }

    static func catalog(type: String, id: String = "top", search: String? = nil) async -> [MetaItem] {
        var path = "\(cinemeta)/catalog/\(type)/\(id)"
        if let search, let q = search.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            path += "/search=\(q)"
        }
        let response = await fetch(CatalogResponse.self, from: path + ".json")
        return (response?.metas ?? []).map { item in
            var m = item
            m.type = type
            return m
        }
    }

    /// Full details: from the add-on that listed a non-Cinemeta title, otherwise Cinemeta, then any add-on with metadata.
    static func meta(type: String, id: String, addonBase: String? = nil, addons: [String] = []) async -> MetaItem? {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var bases: [String] = []
        if let addonBase { bases.append(addonBase) }
        if id.hasPrefix("tt") { bases.insert(cinemeta, at: 0) }
        for base in addons where !bases.contains(base) {
            if let man = await manifest(for: base), man.has("meta") { bases.append(base) }
        }
        if !id.hasPrefix("tt") { bases.append(cinemeta) }
        for base in bases {
            if var m = await fetch(MetaResponse.self, from: "\(base)/meta/\(type)/\(encoded).json")?.meta {
                m.type = type
                if base != cinemeta, !id.hasPrefix("tt") { m.addonBase = base }
                return m
            }
        }
        return nil
    }

    struct Manifest: Decodable {
        var name: String?
        var resources: [Resource]?
        var catalogs: [Catalog]?

        struct Resource: Decodable {
            var name: String
            init(from decoder: Decoder) throws {
                if let s = try? decoder.singleValueContainer().decode(String.self) {
                    name = s
                } else {
                    struct Obj: Decodable { var name: String }
                    name = try Obj(from: decoder).name
                }
            }
        }

        struct Catalog: Decodable {
            var type: String
            var id: String
            var name: String?
            var extra: [Extra]?
            var extraRequired: [String]?

            struct Extra: Decodable {
                var name: String
                var isRequired: Bool?
            }

            /// Can be opened without a search term or genre.
            var isBrowsable: Bool {
                !(extra ?? []).contains { $0.isRequired == true } && (extraRequired ?? []).isEmpty
            }
            var isSearchable: Bool {
                (extra ?? []).contains { $0.name == "search" } || (extraRequired ?? []).contains("search")
            }
        }

        func has(_ resource: String) -> Bool { (resources ?? []).contains { $0.name == resource } }
    }

    /// A browsable catalog from one of the user's add-ons, shown as a row on Home.
    struct AddonCatalog: Hashable {
        var base: String
        var type: String
        var id: String
        var title: String
        var rowId: String { "\(base)|\(type)|\(id)" }
    }

    private static var manifests: [String: Manifest] = [:]
    private static var manifestNames: [String: String] = [:]
    private static let nameLock = NSLock()

    static func manifest(for base: String) async -> Manifest? {
        let cached = nameLock.withLock { manifests[base] }
        if let cached { return cached }
        let m = await fetch(Manifest.self, from: "\(base)/manifest.json")
        if let m {
            nameLock.withLock { manifests[base] = m }
        }
        return m
    }

    /// The add-on's display name from its manifest, used for pinned sources and the source list.
    static func addonName(for base: String) async -> String {
        let cached = nameLock.withLock { manifestNames[base] }
        if let cached { return cached }
        let name = await manifest(for: base)?.name ?? URL(string: base)?.host ?? base
        nameLock.withLock { manifestNames[base] = name }
        return name
    }

    /// Browsable movie and series catalogs from the user's add-ons, in add-on order.
    static func addonCatalogs(_ addons: [String]) async -> [AddonCatalog] {
        var out: [AddonCatalog] = []
        for base in addons {
            guard let m = await manifest(for: base), m.has("catalog") else { continue }
            let addon = m.name ?? URL(string: base)?.host ?? base
            for c in m.catalogs ?? [] where c.isBrowsable && (c.type == "movie" || c.type == "series") {
                let title = c.name.map { "\($0) · \(c.type == "movie" ? "Movies" : "Series")" } ?? addon
                out.append(AddonCatalog(base: base, type: c.type, id: c.id, title: title))
            }
        }
        return out
    }

    static func items(in catalog: AddonCatalog) async -> [MetaItem] {
        let id = catalog.id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? catalog.id
        let list = await fetch(CatalogResponse.self, from: "\(catalog.base)/catalog/\(catalog.type)/\(id).json")?.metas ?? []
        return list.map { item in
            var m = item
            m.type = catalog.type
            if !m.id.hasPrefix("tt") { m.addonBase = catalog.base }
            return m
        }
    }

    /// Searches Cinemeta plus every add-on catalog that supports search, without duplicates.
    static func search(_ query: String, addons: [String]) async -> [MetaItem] {
        async let m = catalog(type: "movie", search: query)
        async let s = catalog(type: "series", search: query)
        var results = await m + s
        var seen = Set(results.map(\.id))
        let q = query.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? query
        for base in addons {
            guard let man = await manifest(for: base), man.has("catalog") else { continue }
            for c in man.catalogs ?? [] where c.isSearchable && (c.type == "movie" || c.type == "series") {
                let id = c.id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? c.id
                let list = await fetch(CatalogResponse.self, from: "\(base)/catalog/\(c.type)/\(id)/search=\(q).json")?.metas ?? []
                for item in list where seen.insert(item.id).inserted {
                    var x = item
                    x.type = c.type
                    if !x.id.hasPrefix("tt") { x.addonBase = base }
                    results.append(x)
                }
            }
        }
        return results
    }

    /// Names of the configured add-ons, in order.
    static func addonNames(_ addons: [String]) async -> [String] {
        var names: [String] = []
        for base in addons { names.append(await addonName(for: base)) }
        return names
    }

    /// Queries every configured add-on in parallel. The list is in add-on order, not ranked. Results marked
    /// "not web ready" (usually MKV debrid links) are kept because mpv plays them; torrent-only results are
    /// kept too, and the stream store drops them unless a debrid service is set up.
    static func streams(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var perAddon: [Int: [StreamItem]] = [:]
        await withTaskGroup(of: (Int, [StreamItem]).self) { group in
            for (index, base) in addons.enumerated() {
                group.addTask {
                    let name = await addonName(for: base)
                    var list = await fetch(StreamResponse.self, from: "\(base)/stream/\(type)/\(encoded).json")?.streams ?? []
                    for i in list.indices { list[i].addonName = name }
                    return (index, list)
                }
            }
            for await (index, list) in group { perAddon[index] = list }
        }
        var all: [StreamItem] = []
        for index in addons.indices { all.append(contentsOf: perAddon[index] ?? []) }
        return all.filter { $0.playableURL != nil || $0.isTorrentOnly }
    }

    /// Subtitles from every add-on that offers them, in add-on order.
    static func subtitles(type: String, id: String, addons: [String]) async -> [SubtitleItem] {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        var perAddon: [Int: [SubtitleItem]] = [:]
        await withTaskGroup(of: (Int, [SubtitleItem]).self) { group in
            for (index, base) in addons.enumerated() {
                group.addTask {
                    guard let man = await manifest(for: base), man.has("subtitles") else { return (index, []) }
                    let name = man.name ?? URL(string: base)?.host
                    var list = await fetch(SubtitleResponse.self, from: "\(base)/subtitles/\(type)/\(encoded).json")?.subtitles ?? []
                    for i in list.indices { list[i].addonName = name }
                    return (index, list)
                }
            }
            for await (index, list) in group { perAddon[index] = list }
        }
        var all: [SubtitleItem] = []
        var seen = Set<String>()
        for index in addons.indices {
            for s in perAddon[index] ?? [] where seen.insert(s.url).inserted { all.append(s) }
        }
        return all
    }
}
