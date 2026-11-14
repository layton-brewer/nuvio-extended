import SwiftUI

// MARK: - Navigation

/// Opens a title and starts the best source straight away (Continue Watching, the hero's Play button).
struct AutoPlayRoute: Hashable {
    var item: MetaItem
}

extension View {
    /// Detail pages for every navigation stack in the app.
    func meowioDestinations() -> some View {
        self
            .navigationDestination(for: MetaItem.self) { DetailView(item: $0) }
            .navigationDestination(for: AutoPlayRoute.self) { DetailView(item: $0.item, autoplay: true) }
    }
}

// MARK: - Artwork

/// Remote artwork with a soft placeholder, so rows never jump while images load.
struct Artwork: View {
    let url: URL?
    var placeholder: String = ""
    var contentMode: ContentMode = .fill

    var body: some View {
        AsyncImage(url: url, transaction: Transaction(animation: .easeInOut(duration: 0.25))) { phase in
            switch phase {
            case .success(let image):
                image.resizable().aspectRatio(contentMode: contentMode)
            default:
                ZStack {
                    Rectangle().fill(.ultraThinMaterial)
                    if !placeholder.isEmpty {
                        Text(placeholder).font(.callout.bold()).multilineTextAlignment(.center).padding()
                    }
                }
            }
        }
    }
}

/// Full-screen backdrop behind Home and detail pages, faded into the theme colour.
struct Backdrop: View {
    let url: URL?
    let background: Color

    var body: some View {
        ZStack {
            background
            if let url {
                Artwork(url: url)
                    .id(url)
                    .transition(.opacity)
            }
            LinearGradient(colors: [background, background.opacity(0.75), background.opacity(0.15)],
                           startPoint: .leading, endPoint: .trailing)
            LinearGradient(colors: [.clear, background.opacity(0.6), background],
                           startPoint: .center, endPoint: .bottom)
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.4), value: url)
    }
}

// MARK: - Cards

struct PosterCard: View {
    let item: MetaItem
    var onFocus: ((MetaItem) -> Void)? = nil
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Artwork(url: item.posterURL, placeholder: item.name)
                .frame(width: 220, height: 330)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            Text(item.name)
                .font(.caption)
                .lineLimit(1)
                .frame(width: 220, alignment: .leading)
                .opacity(focused ? 1 : 0.7)
        }
        .onChange(of: focused) { _, f in if f { onFocus?(item) } }
    }
}

/// Wide card for Continue Watching, with how far the user got.
struct ContinueCard: View {
    let entry: ContinueItem
    let subtitle: String?
    let progress: Double?
    var onFocus: ((MetaItem) -> Void)? = nil
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .bottomLeading) {
                Artwork(url: entry.meta.backgroundURL, placeholder: entry.meta.name)
                    .frame(width: 420, height: 236)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                HStack(spacing: 10) {
                    Image(systemName: "play.fill")
                    Text(subtitle ?? "Resume").font(.caption.bold())
                }
                .padding(16)
                if let progress {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(.white.opacity(0.25))
                            Capsule().fill(.white).frame(width: geo.size.width * progress)
                        }
                    }
                    .frame(height: 6)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 4)
                }
            }
            .frame(width: 420, height: 236)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            Text(entry.meta.name).font(.caption).lineLimit(1).frame(width: 420, alignment: .leading)
                .opacity(focused ? 1 : 0.7)
        }
        .onChange(of: focused) { _, f in if f { onFocus?(entry.meta) } }
    }
}

struct EpisodeCard: View {
    let video: Video
    let fallback: URL?
    let watched: Bool
    let progress: Double?
    let isNext: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomLeading) {
                Artwork(url: video.thumbnailURL ?? fallback, placeholder: "Episode \(video.episode ?? 0)")
                    .frame(width: 400, height: 225)
                LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                HStack {
                    Text("E\(video.episode ?? 0)").font(.caption.bold())
                    Spacer()
                    if watched { Image(systemName: "checkmark.circle.fill") }
                    else if isNext { Image(systemName: "play.circle.fill") }
                }
                .padding(14)
                if let progress {
                    Capsule().fill(.white.opacity(0.25)).frame(height: 5)
                        .overlay(alignment: .leading) {
                            GeometryReader { g in Capsule().fill(.white).frame(width: g.size.width * progress) }
                        }
                        .padding(.horizontal, 14).padding(.bottom, 3)
                }
            }
            .frame(width: 400, height: 225)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            Text(video.displayTitle).font(.caption).lineLimit(1).frame(width: 400, alignment: .leading)
                .opacity(focused ? 1 : 0.7)
        }
    }
}

// MARK: - Rows

struct Shelf: View {
    let title: String
    let items: [MetaItem]
    var onFocus: ((MetaItem) -> Void)? = nil

    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.title3.bold()).padding(.leading, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 40) {
                        ForEach(items) { item in
                            NavigationLink(value: item) { PosterCard(item: item, onFocus: onFocus) }
                                .buttonStyle(.card)
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }
}

struct ContinueShelf: View {
    @EnvironmentObject var library: LibraryStore
    var onFocus: ((MetaItem) -> Void)? = nil

    var body: some View {
        if !library.continueWatching.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("Continue Watching").font(.title3.bold()).padding(.leading, 60)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 40) {
                        ForEach(library.continueWatching) { entry in
                            NavigationLink(value: AutoPlayRoute(item: entry.meta)) {
                                ContinueCard(entry: entry, subtitle: subtitle(entry),
                                             progress: library.progress(for: entry.videoId ?? entry.id), onFocus: onFocus)
                            }
                            .buttonStyle(.card)
                            .contextMenu {
                                Button("Remove from Continue Watching", role: .destructive) { library.removeContinue(entry.id) }
                            }
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.vertical, 24)
                }
                .scrollClipDisabled()
            }
        }
    }

    private func subtitle(_ entry: ContinueItem) -> String? {
        guard let vid = entry.videoId else { return "Resume" }
        // Cinemeta episode ids look like tt123:1:4.
        let parts = vid.split(separator: ":")
        if parts.count >= 3, let s = Int(parts[parts.count - 2]), let e = Int(parts[parts.count - 1]) {
            return "S\(s) E\(e)"
        }
        return "Resume"
    }
}

// MARK: - Home

/// The big header at the top of Home: the focused title's art, info and a one-press Play.
struct HeroHeader: View {
    @EnvironmentObject var profiles: ProfileStore
    let item: MetaItem
    let accent: Color
    let resumeLabel: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("MEOWIO")
                .font(.caption.weight(.heavy))
                .kerning(6)
                .foregroundStyle(accent)
                .fixedSize()
            Text(item.name)
                .font(.system(size: 64, weight: .bold))
                .lineLimit(2)
                .minimumScaleFactor(0.6)
                .frame(maxWidth: 1100, alignment: .leading)
            HStack(spacing: 18) {
                Text(item.type == "movie" ? "Movie" : "Series").padding(.horizontal, 12).padding(.vertical, 4).glass(radius: 8)
                if let r = item.releaseInfo { Text(r) }
                if let r = item.imdbRating { Label(r, systemImage: "star.fill") }
                if let g = item.genres, !g.isEmpty { Text(g.prefix(3).joined(separator: " · ")) }
            }
            .font(.callout)
            .foregroundStyle(.secondary)
            if let d = item.description {
                Text(d).font(.callout).lineLimit(3).frame(maxWidth: 1000, alignment: .leading).foregroundStyle(.secondary)
            }
            HStack(spacing: 24) {
                NavigationLink(value: AutoPlayRoute(item: item)) {
                    Label(resumeLabel ?? "Play", systemImage: "play.fill").padding(.horizontal, 24)
                }
                NavigationLink(value: item) {
                    Label("More Info", systemImage: "info.circle").padding(.horizontal, 12)
                }
                if let profile = profiles.active, profiles.profiles.count > 1 {
                    Button { profiles.showPicker() } label: {
                        HStack(spacing: 12) {
                            ProfileAvatar(profile: profile, size: 36)
                            Text(profile.name)
                        }
                    }
                }
            }
            .padding(.top, 8)
        }
        .padding(.horizontal, 60)
        .padding(.top, 40)
        .frame(maxWidth: .infinity, minHeight: 600, alignment: .bottomLeading)
    }
}

struct AddonRow: Identifiable {
    var catalog: AddonClient.AddonCatalog
    var items: [MetaItem]
    var id: String { catalog.rowId }
}

struct HomeView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore
    @State private var movies: [MetaItem] = []
    @State private var series: [MetaItem] = []
    @State private var addonRows: [AddonRow] = []
    @State private var loaded = false
    @State private var focusedItem: MetaItem?
    @State private var heroItem: MetaItem?
    @StateObject private var updates = UpdateChecker()

    private var featured: MetaItem? { heroItem ?? library.continueWatching.first?.meta ?? movies.first ?? series.first }
    private var backdropItem: MetaItem? { focusedItem ?? featured }

    var body: some View {
        NavigationStack {
            ZStack {
                Backdrop(url: backdropItem?.backgroundURL, background: settings.theme.background)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 20) {
                        if let featured {
                            HeroHeader(item: featured, accent: settings.theme.accent,
                                       resumeLabel: library.continueWatching.contains { $0.id == featured.id } ? "Resume" : nil)
                        } else {
                            Text("Meowio").font(.largeTitle.bold()).foregroundStyle(settings.theme.accent)
                                .padding(.leading, 60).padding(.top, 40)
                        }
                        if settings.addons.isEmpty {
                            Text("Add a stream add-on in Settings and Meowio will pick and play sources for you.")
                                .padding(24).glass().padding(.horizontal, 60)
                        }
                        if case .available(let tag) = updates.state {
                            Label("Meowio \(tag) is out. Get Meowio-tvos.ipa from the Meowio releases page on GitHub and sideload it over this version.",
                                  systemImage: "arrow.down.circle")
                                .font(.callout)
                                .padding(24).glass().padding(.horizontal, 60)
                        }
                        ContinueShelf(onFocus: focus)
                        Shelf(title: "Popular Movies", items: movies, onFocus: focus)
                        Shelf(title: "Popular Series", items: series, onFocus: focus)
                        ForEach(addonRows) { row in
                            Shelf(title: row.catalog.title, items: row.items, onFocus: focus)
                        }
                        if loaded && movies.isEmpty && series.isEmpty && addonRows.isEmpty {
                            Text("Couldn't load the catalog. Check your connection.").padding(60)
                        }
                    }
                    .padding(.bottom, 80)
                }
            }
            .meowioDestinations()
        }
        .task {
            if case .idle = updates.state { await updates.check() }
        }
        .task {
            guard !loaded else { return }
            async let m = AddonClient.catalog(type: "movie")
            async let s = AddonClient.catalog(type: "series")
            movies = await m
            series = await s
            heroItem = movies.prefix(5).randomElement()
            loaded = true
        }
        .task(id: settings.addons) {
            let catalogs = await AddonClient.addonCatalogs(settings.addons)
            var rows: [AddonRow] = []
            for c in catalogs.prefix(12) {
                let items = await AddonClient.items(in: c)
                // Skip rows that only repeat Cinemeta's popular lists.
                if !items.isEmpty, c.base != AddonClient.cinemeta { rows.append(AddonRow(catalog: c, items: Array(items.prefix(40)))) }
            }
            addonRows = rows
        }
    }

    private func focus(_ item: MetaItem) {
        focusedItem = item
    }
}

// MARK: - Search and library

struct SearchView: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var query = ""
    @State private var results: [MetaItem] = []
    @State private var searching = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    if searching { ProgressView().padding(60) }
                    Shelf(title: "Movies", items: results.filter { $0.type == "movie" })
                    Shelf(title: "Series", items: results.filter { $0.type == "series" })
                    if !searching && results.isEmpty && query.count >= 2 {
                        Text("Nothing found for \u{201C}\(query)\u{201D}.").padding(60)
                    }
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .meowioDestinations()
        }
        .searchable(text: $query)
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard q.count >= 2 else { results = []; return }
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            searching = true
            let found = await AddonClient.search(q, addons: settings.addons)
            if Task.isCancelled { return }
            results = found
            searching = false
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    Text("Library").font(.largeTitle.bold()).padding(.leading, 60)
                    ContinueShelf()
                    Shelf(title: "Saved Movies", items: library.saved.filter { $0.type == "movie" })
                    Shelf(title: "Saved Series", items: library.saved.filter { $0.type != "movie" })
                    if library.saved.isEmpty && library.continueWatching.isEmpty {
                        Text("Save titles from their detail page and they will show up here.").padding(60)
                    }
                }
            }
            .background(settings.theme.background.ignoresSafeArea())
            .meowioDestinations()
        }
    }
}

// MARK: - Detail

struct DetailView: View {
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var streams: StreamStore
    let item: MetaItem
    var autoplay = false
    @State private var full: MetaItem?
    @State private var season = 1
    @State private var playRequest: PlayRequest?
    @State private var resolving = false
    @State private var message: String?
    @State private var picker: PickerState?
    @State private var didAutoplay = false
    @FocusState private var playFocused: Bool

    struct PickerState: Identifiable {
        let id = UUID()
        let videoId: String
        let title: String
        let candidates: [StreamItem]
    }

    private var meta: MetaItem { full ?? item }
    private var seasons: [Int] {
        Array(Set((meta.videos ?? []).compactMap(\.season).filter { $0 > 0 })).sorted()
    }
    private var episodes: [Video] {
        (meta.videos ?? []).filter { $0.season == season }.sorted { ($0.episode ?? 0) < ($1.episode ?? 0) }
    }
    /// Episodes in watching order, specials left out.
    private var ordered: [Video] {
        (meta.videos ?? []).filter { ($0.season ?? 0) > 0 }
            .sorted { (($0.season ?? 0), ($0.episode ?? 0)) < (($1.season ?? 0), ($1.episode ?? 0)) }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Backdrop(url: meta.backgroundURL, background: settings.theme.background)
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    Spacer(minLength: 120)
                    Text(meta.name)
                        .font(.system(size: 64, weight: .bold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.6)
                        .frame(maxWidth: 1200, alignment: .leading)
                    HStack(spacing: 20) {
                        if let r = meta.releaseInfo { Text(r) }
                        if let r = meta.imdbRating { Label(r, systemImage: "star.fill") }
                        if let g = meta.genres, !g.isEmpty { Text(g.prefix(3).joined(separator: " · ")) }
                    }
                    .foregroundStyle(.secondary)
                    if let d = meta.description {
                        Text(d).font(.callout).lineLimit(4).frame(maxWidth: 1100, alignment: .leading)
                    }
                    HStack(spacing: 24) {
                        if meta.type == "movie" {
                            Button { Task { await play(videoId: meta.id, title: meta.name) } } label: {
                                Label(resolving ? "Finding best source…" : movieLabel, systemImage: "play.fill")
                                    .padding(.horizontal, 30)
                            }
                            .focused($playFocused)
                            .disabled(resolving)
                        } else if let next = nextUp {
                            Button { Task { await play(videoId: next.id, title: next.displayTitle) } } label: {
                                Label(resolving ? "Finding best source…" : nextLabel(next), systemImage: "play.fill")
                                    .padding(.horizontal, 30)
                            }
                            .focused($playFocused)
                            .disabled(resolving)
                        }
                        Button { library.toggleSaved(meta) } label: {
                            Label(library.isSaved(meta) ? "Saved" : "My List", systemImage: library.isSaved(meta) ? "checkmark" : "plus")
                        }
                        if meta.type == "movie" {
                            Button {
                                if library.isWatched(meta.id) { library.markUnwatched(meta: meta, videoId: nil) }
                                else { library.markWatched(meta: meta, videoId: nil) }
                            } label: {
                                Label(library.isWatched(meta.id) ? "Watched" : "Mark watched",
                                      systemImage: library.isWatched(meta.id) ? "eye.fill" : "eye")
                            }
                        }
                        if settings.streamMode == .smart, !settings.addons.isEmpty {
                            Button { Task { await chooseSource() } } label: {
                                Label("Sources", systemImage: "list.bullet")
                            }
                            .disabled(resolving)
                        }
                    }
                    if let message { Text(message).foregroundStyle(.orange) }
                    if meta.type != "movie" { episodesSection }
                }
                .padding(.horizontal, 80)
                .padding(.bottom, 80)
            }
            .scrollClipDisabled()
        }
        .task {
            full = await AddonClient.meta(type: item.type, id: item.id, addonBase: item.addonBase, addons: settings.addons)
            if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
               let s = (full?.videos ?? []).first(where: { $0.id == resume })?.season, s > 0 {
                season = s
            } else if let first = seasons.first {
                season = seasons.contains(1) ? 1 : first
            }
            playFocused = true
            preloadForOpen()
            if autoplay, !didAutoplay {
                didAutoplay = true
                if meta.type == "movie" {
                    await play(videoId: meta.id, title: meta.name)
                } else if let next = nextUp {
                    await play(videoId: next.id, title: next.displayTitle)
                }
            }
        }
        .fullScreenCover(item: $playRequest) { req in
            PlayerScreen(request: req, settings: settings)
        }
        .sheet(item: $picker) { state in
            SourcePicker(state: state) { chosen in
                picker = nil
                start(videoId: state.videoId, title: state.title, candidates: chosen)
            }
        }
    }

    @ViewBuilder private var episodesSection: some View {
        if seasons.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 16) {
                    ForEach(seasons, id: \.self) { s in
                        Button { season = s } label: {
                            Text("Season \(s)").fontWeight(s == season ? .bold : .regular)
                        }
                        .tint(s == season ? settings.theme.accent : nil)
                    }
                }
                .padding(.vertical, 16)
            }
            .scrollClipDisabled()
        } else if let s = seasons.first {
            Text("Season \(s)").font(.title3.bold())
        }
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 36) {
                    ForEach(episodes) { ep in
                        Button { Task { await play(videoId: ep.id, title: ep.displayTitle) } } label: {
                            EpisodeCard(video: ep, fallback: meta.backgroundURL, watched: watched(ep),
                                        progress: library.progress(for: ep.id), isNext: ep.id == nextUp?.id)
                        }
                        .buttonStyle(.card)
                        .disabled(resolving)
                        .contextMenu {
                            if watched(ep) {
                                Button("Mark as unwatched") { library.markUnwatched(meta: meta, videoId: ep.id) }
                            } else {
                                Button("Mark as watched") { library.markWatched(meta: meta, videoId: ep.id) }
                            }
                            Button("Mark season \(season) as watched") {
                                for e in episodes where !watched(e) { library.markWatched(meta: meta, videoId: e.id) }
                            }
                            Button("Choose source") { Task { await play(videoId: ep.id, title: ep.displayTitle, forcePicker: true) } }
                        }
                        .id(ep.id)
                    }
                }
                .padding(.vertical, 24)
            }
            .scrollClipDisabled()
            .onChange(of: season) { _, _ in
                if let first = episodes.first { proxy.scrollTo(first.id, anchor: .leading) }
            }
            .onAppear {
                if let n = nextUp, episodes.contains(where: { $0.id == n.id }) { proxy.scrollTo(n.id, anchor: .leading) }
            }
        }
        if let ep = episodes.first(where: { $0.id == nextUp?.id }) ?? episodes.first, let title = ep.title ?? ep.name {
            Text("Up next: E\(ep.episode ?? 0) \(title)").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var movieLabel: String {
        library.resumePoint(for: meta.id) > 0 ? "Resume" : "Play"
    }

    private func watched(_ ep: Video) -> Bool { library.isWatched(ep.id) }

    /// The first episode not finished yet, or the last one watched when it is only partly seen.
    private var nextUp: Video? {
        let list = ordered
        guard !list.isEmpty else { return nil }
        if let resume = library.continueWatching.first(where: { $0.id == item.id })?.videoId,
           let i = list.firstIndex(where: { $0.id == resume }) {
            if library.records.contains(where: { $0.id == resume && $0.completed }), i + 1 < list.count { return list[i + 1] }
            return list[i]
        }
        return list.first
    }

    private func nextLabel(_ v: Video) -> String {
        let started = library.resumePoint(for: v.id) > 0
        return "\(started ? "Resume" : "Play") S\(v.season ?? 0) E\(v.episode ?? 0)"
    }

    /// Finds sources early: the movie, or the next episode up plus the first episode of every season.
    private func preloadForOpen() {
        if meta.type == "movie" {
            streams.preload(type: "movie", id: meta.id, settings: settings)
            return
        }
        if let n = nextUp { streams.preload(type: "series", id: n.id, settings: settings) }
        for s in seasons.prefix(8) {
            if let first = (meta.videos ?? []).filter({ $0.season == s }).min(by: { ($0.episode ?? 0) < ($1.episode ?? 0) }) {
                streams.preload(type: "series", id: first.id, settings: settings)
            }
        }
    }

    private func play(videoId: String, title: String, forcePicker: Bool = false) async {
        message = nil
        guard !settings.addons.isEmpty else {
            message = "Add a stream add-on in Settings first."
            return
        }
        resolving = true
        let list = await streams.candidates(type: meta.type, id: videoId, parentId: meta.id, settings: settings)
        resolving = false
        guard !list.isEmpty else {
            message = "No playable sources found. Check your add-ons in Settings, then try again, or use the Sources button."
            return
        }
        if forcePicker || settings.streamMode == .manual {
            picker = PickerState(videoId: videoId, title: title, candidates: list)
        } else {
            start(videoId: videoId, title: title, candidates: list)
        }
    }

    /// Opens the ranked source list for the movie or the next episode, even in Smart mode.
    private func chooseSource() async {
        if meta.type == "movie" {
            await play(videoId: meta.id, title: meta.name, forcePicker: true)
        } else if let n = nextUp {
            await play(videoId: n.id, title: n.displayTitle, forcePicker: true)
        }
    }

    private func start(videoId: String, title: String, candidates: [StreamItem]) {
        library.record(meta: meta, videoId: meta.type == "movie" ? nil : videoId)
        var req = makeRequest(videoId: videoId, title: title, candidates: candidates)
        req.startAt = library.resumePoint(for: meta.type == "movie" ? meta.id : videoId)
        playRequest = req
    }

    private func makeRequest(videoId: String, title: String, candidates: [StreamItem]) -> PlayRequest {
        if meta.type == "series", let cur = ordered.first(where: { $0.id == videoId }) {
            return DetailView.chain(meta: meta, ordered: ordered, current: cur, candidates: candidates,
                                    streams: streams, settings: settings, library: library)
        }
        return PlayRequest(meta: meta, videoId: meta.type == "movie" ? nil : videoId, title: title, candidates: candidates)
    }

    /// Builds the request for [current] and, recursively, for the episode after it.
    @MainActor
    static func chain(meta: MetaItem, ordered: [Video], current: Video, candidates: [StreamItem],
                      streams: StreamStore, settings: SettingsStore, library: LibraryStore) -> PlayRequest {
        var slim = meta
        slim.videos = nil
        var req = PlayRequest(meta: slim, videoId: current.id, title: current.displayTitle, candidates: candidates)
        if let idx = ordered.firstIndex(where: { $0.id == current.id }), idx + 1 < ordered.count {
            let next = ordered[idx + 1]
            if settings.preloadNext { streams.preload(type: "series", id: next.id, settings: settings) }
            req.nextEpisode = {
                let list = await streams.candidates(type: "series", id: next.id, parentId: meta.id, settings: settings)
                guard !list.isEmpty else { return nil }
                library.record(meta: meta, videoId: next.id)
                var nextReq = DetailView.chain(meta: meta, ordered: ordered, current: next, candidates: list,
                                               streams: streams, settings: settings, library: library)
                nextReq.startAt = library.resumePoint(for: next.id)
                return nextReq
            }
        }
        return req
    }
}

/// The ranked source list: Manual mode, or the Sources button in Smart mode.
struct SourcePicker: View {
    let state: DetailView.PickerState
    let onChoose: ([StreamItem]) -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(state.candidates.prefix(40).enumerated()), id: \.offset) { i, s in
                    Button {
                        var reordered = state.candidates
                        let picked = reordered.remove(at: i)
                        reordered.insert(picked, at: 0)
                        onChoose(reordered)
                    } label: {
                        HStack(alignment: .top, spacing: 20) {
                            if i == 0 {
                                Image(systemName: "sparkles").foregroundStyle(.yellow)
                            }
                            VStack(alignment: .leading) {
                                Text(s.label).font(.headline)
                                Text("\(s.addonName ?? "")  \(s.detail)").font(.caption).lineLimit(2)
                            }
                        }
                    }
                }
            }
            .navigationTitle(state.title)
        }
    }
}
