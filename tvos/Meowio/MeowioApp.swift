import SwiftUI

@main
struct MeowioApp: App {
    @StateObject private var profiles = ProfileStore()
    @StateObject private var account = AccountStore()

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .environmentObject(profiles)
                .environmentObject(account)
                .preferredColorScheme(.dark)
        }
    }
}

/// "Who's watching?" first when needed, then the app for the chosen profile.
struct AppRoot: View {
    @EnvironmentObject var profiles: ProfileStore
    @EnvironmentObject var account: AccountStore

    var body: some View {
        Group {
            if let profile = profiles.active {
                // A new identity per profile, so every store below is made fresh from that profile's storage.
                ProfileRoot(profile: profile).id(profile.id)
            } else {
                ProfilePickerView()
            }
        }
        .task { await account.pullProfiles(into: profiles) }
    }
}

/// Everything for one profile: its settings, library, sources, Live TV and Trakt link.
struct ProfileRoot: View {
    let profile: Profile
    @EnvironmentObject var account: AccountStore
    @EnvironmentObject var profiles: ProfileStore
    @StateObject private var settings: SettingsStore
    @StateObject private var library = LibraryStore()
    @StateObject private var streams = StreamStore()
    @StateObject private var live = LiveTVStore()
    @StateObject private var trakt = TraktStore()
    @Environment(\.scenePhase) private var scenePhase

    init(profile: Profile) {
        self.profile = profile
        ProfileScope.activeId = profile.id
        _settings = StateObject(wrappedValue: SettingsStore(sharesPrimaryAddons: profile.usesPrimaryAddons))
    }

    var body: some View {
        RootView()
            .environmentObject(settings)
            .environmentObject(library)
            .environmentObject(streams)
            .environmentObject(live)
            .environmentObject(trakt)
            .tint(settings.theme.accent)
            .task {
                wire()
                await syncAll()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await syncAll() } }
            }
            .onChange(of: settings.addons) { _, addons in
                guard !profile.usesPrimaryAddons || profile.id == 1 else { return }
                Task { await account.pushAddons(addons, profileId: profile.id) }
            }
    }

    private func syncAll() async {
        if account.signedIn {
            await account.sync(profile: profile, settings: settings, library: library)
        }
        if trakt.connected { await trakt.sync(into: library) }
    }

    /// My List and play progress go to the account and Trakt as they change.
    private func wire() {
        let id = profile.id
        let acc = account
        let tr = trakt
        library.onSavedChanged = { [weak acc, weak tr] item, added in
            Task { @MainActor in
                tr?.setInWatchlist(added, meta: item)
                if added { await acc?.pushLibrary([item], profileId: id) }
                else { await acc?.removeFromLibrary(item, profileId: id) }
            }
        }
        library.onRecordChanged = { [weak acc] record, meta in
            Task { @MainActor in
                if record.completed {
                    await acc?.pushWatched([record], profileId: id)
                } else if record.seconds > 0 {
                    await acc?.pushProgress([record], profileId: id, metaType: meta?.type)
                } else {
                    await acc?.removeWatched(record, profileId: id)
                }
            }
        }
        // Played titles reach Trakt through scrobbling. Marks made by hand go straight to its history.
        library.onManualMark = { [weak tr] meta, videoId, watched in
            tr?.setWatched(watched, meta: meta, videoId: videoId)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var settings: SettingsStore

    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
            SearchView()
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
            LibraryView()
                .tabItem { Label("Library", systemImage: "bookmark.fill") }
            if settings.liveTabVisible {
                LiveTVView()
                    .tabItem { Label("Live TV", systemImage: "antenna.radiowaves.left.and.right") }
            }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .background(settings.theme.background.ignoresSafeArea())
    }
}
