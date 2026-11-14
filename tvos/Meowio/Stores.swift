import Foundation
import SwiftUI

/// Continue watching, saved titles and the watch records behind the statistics.
@MainActor
final class LibraryStore: ObservableObject {
    @Published var continueWatching: [ContinueItem] = []
    @Published var saved: [MetaItem] = []
    @Published var records: [WatchRecord] = []
    /// This profile's storage, fixed when the store is made so a profile switch never writes into another profile.
    private let store = ProfileScope.defaults
    /// Called when a title is added to (true) or removed from (false) My List. Account sync and Trakt listen.
    var onSavedChanged: ((MetaItem, Bool) -> Void)?
    /// Called after a play position is stored, and when something is marked watched or unwatched.
    var onRecordChanged: ((WatchRecord, MetaItem?) -> Void)?
    /// Called when the user marks something watched or unwatched by hand.
    var onManualMark: ((MetaItem, String?, Bool) -> Void)?

    init() {
        let d = store
        if let data = d.data(forKey: "continue"), let items = try? JSONDecoder().decode([ContinueItem].self, from: data) {
            continueWatching = items
        }
        if let data = d.data(forKey: "saved"), let items = try? JSONDecoder().decode([MetaItem].self, from: data) {
            saved = items
        }
        if let data = d.data(forKey: "records"), let items = try? JSONDecoder().decode([WatchRecord].self, from: data) {
            records = items
        }
    }

    private func persist() {
        let d = store
        if let data = try? JSONEncoder().encode(continueWatching) { d.set(data, forKey: "continue") }
        if let data = try? JSONEncoder().encode(saved) { d.set(data, forKey: "saved") }
        if let data = try? JSONEncoder().encode(records) { d.set(data, forKey: "records") }
    }

    func record(meta: MetaItem, videoId: String?) {
        var slim = meta
        slim.videos = nil
        continueWatching.removeAll { $0.id == meta.id }
        continueWatching.insert(ContinueItem(id: meta.id, meta: slim, videoId: videoId, updated: Date()), at: 0)
        if continueWatching.count > 30 { continueWatching.removeLast() }
        persist()
    }

    func isSaved(_ meta: MetaItem) -> Bool { saved.contains { $0.id == meta.id } }

    func toggleSaved(_ meta: MetaItem) {
        var slim = meta
        slim.videos = nil
        let adding: Bool
        if let i = saved.firstIndex(where: { $0.id == meta.id }) {
            saved.remove(at: i)
            adding = false
        } else {
            saved.insert(slim, at: 0)
            adding = true
        }
        persist()
        onSavedChanged?(slim, adding)
    }

    /// Where to pick up a title or episode that was left part way, or 0 to start at the beginning.
    func resumePoint(for itemId: String) -> Double {
        guard let r = records.last(where: { $0.id == itemId }), !r.completed, r.seconds > 30 else { return 0 }
        return max(0, r.seconds - 5)
    }

    /// How far through a title or episode the user got, 0...1, for progress bars.
    func progress(for itemId: String) -> Double? {
        guard let r = records.last(where: { $0.id == itemId }), !r.completed, let d = r.duration, d > 0 else { return nil }
        return min(1, max(0, r.seconds / d))
    }

    func removeContinue(_ id: String) {
        continueWatching.removeAll { $0.id == id }
        persist()
    }

    /// Stores how far an item was played, for the watch statistics.
    func recordPlay(meta: MetaItem, videoId: String?, seconds: Double, duration: Double? = nil, completed: Bool) {
        let itemId = videoId ?? meta.id
        let isEpisode = videoId != nil && meta.type == "series"
        records.removeAll { $0.id == itemId }
        records.append(WatchRecord(id: itemId, contentId: meta.id, title: meta.name, isEpisode: isEpisode,
                                   seconds: seconds, completed: completed, at: Date(), duration: duration))
        if records.count > 2000 { records.removeFirst(records.count - 2000) }
        persist()
        if let r = records.last { onRecordChanged?(r, meta) }
    }

    func isWatched(_ itemId: String) -> Bool { records.contains { $0.id == itemId && $0.completed } }

    /// Marks a movie or an episode as finished (from the episode menu, Trakt or account sync).
    func markWatched(meta: MetaItem, videoId: String?, notify: Bool = true, at date: Date = Date()) {
        let itemId = videoId ?? meta.id
        if let i = records.lastIndex(where: { $0.id == itemId }) {
            guard !records[i].completed else { return }
            records[i].completed = true
            records[i].at = date
        } else {
            records.append(WatchRecord(id: itemId, contentId: meta.id, title: meta.name,
                                       isEpisode: videoId != nil && meta.type == "series",
                                       seconds: 0, completed: true, at: date))
        }
        persist()
        if notify, let r = records.last(where: { $0.id == itemId }) {
            onRecordChanged?(r, meta)
            onManualMark?(meta, videoId, true)
        }
    }

    func markUnwatched(meta: MetaItem, videoId: String?) {
        let itemId = videoId ?? meta.id
        guard let r = records.last(where: { $0.id == itemId }) else { return }
        records.removeAll { $0.id == itemId }
        persist()
        var cleared = r
        cleared.completed = false
        cleared.seconds = 0
        onRecordChanged?(cleared, meta)
        onManualMark?(meta, videoId, false)
    }

    /// Adds titles from the account or Trakt to My List without sending them back.
    func mergeSaved(_ items: [MetaItem]) {
        var changed = false
        for item in items where !saved.contains(where: { $0.id == item.id }) {
            saved.append(item)
            changed = true
        }
        if changed { persist() }
    }

    /// Takes a play position from the account or Trakt when it is newer than the one stored here.
    func mergeProgress(itemId: String, meta: MetaItem, isEpisode: Bool, seconds: Double, duration: Double?,
                       completed: Bool, at date: Date) {
        if let existing = records.last(where: { $0.id == itemId }), existing.at >= date { return }
        records.removeAll { $0.id == itemId }
        records.append(WatchRecord(id: itemId, contentId: meta.id, title: meta.name, isEpisode: isEpisode,
                                   seconds: seconds, completed: completed, at: date, duration: duration))
        records.sort { $0.at < $1.at }
        if !completed, seconds > 30, !continueWatching.contains(where: { $0.id == meta.id && $0.updated >= date }) {
            var slim = meta
            slim.videos = nil
            continueWatching.removeAll { $0.id == meta.id }
            let entry = ContinueItem(id: meta.id, meta: slim, videoId: isEpisode ? itemId : nil, updated: date)
            let at = continueWatching.firstIndex { $0.updated < date } ?? continueWatching.count
            continueWatching.insert(entry, at: at)
            if continueWatching.count > 30 { continueWatching.removeLast() }
        }
        persist()
    }

    /// Fills in names and artwork for Continue Watching entries that came from sync with only an id.
    func refreshContinueMeta(_ meta: MetaItem) {
        var slim = meta
        slim.videos = nil
        var changed = false
        for i in continueWatching.indices where continueWatching[i].id == meta.id && continueWatching[i].meta.poster == nil {
            continueWatching[i].meta = slim
            changed = true
        }
        for i in records.indices where records[i].contentId == meta.id && records[i].title == meta.id {
            records[i].title = meta.name
            changed = true
        }
        if changed { persist() }
    }
}

/// Finds and caches source candidates per title or episode so playback can start at once.
/// Preloading simply asks for the candidates early.
@MainActor
final class StreamStore: ObservableObject {
    private var cache: [String: [StreamItem]] = [:]
    private var inflight: [String: Task<[StreamItem], Never>] = [:]
    /// The release (binge group or file name) that last played for each show.
    private var lastRelease: [String: String] = [:]

    private func key(_ type: String, _ id: String, _ addons: [String]) -> String {
        "\(type)/\(id)|\(addons.joined(separator: ","))"
    }

    private func raw(type: String, id: String, addons: [String]) async -> [StreamItem] {
        let k = key(type, id, addons)
        if let hit = cache[k], !hit.isEmpty { return hit }
        if let task = inflight[k] { return await task.value }
        let task = Task<[StreamItem], Never> { await AddonClient.streams(type: type, id: id, addons: addons) }
        inflight[k] = task
        let result = await task.value
        inflight[k] = nil
        if !result.isEmpty { cache[k] = result }
        return result
    }

    /// Ranked candidates, best first.
    func candidates(type: String, id: String, parentId: String, settings: SettingsStore) async -> [StreamItem] {
        var list = await raw(type: type, id: id, addons: settings.addons)
        if !settings.debridReady { list.removeAll { $0.isTorrentOnly } }
        var ranked = SmartRanker.rank(list, isSeries: type == "series", pinned: Set(settings.pinnedSources))
        if type == "series", settings.sameRelease, let release = lastRelease[parentId],
           let i = ranked.firstIndex(where: { releaseKey($0) == release }), i > 0 {
            let match = ranked.remove(at: i)
            ranked.insert(match, at: 0)
        }
        return ranked
    }

    func preload(type: String, id: String, settings: SettingsStore) {
        guard settings.streamMode == .smart, !settings.addons.isEmpty else { return }
        let addons = settings.addons
        Task { _ = await raw(type: type, id: id, addons: addons) }
    }

    func rememberRelease(parentId: String, stream: StreamItem) {
        if let k = releaseKey(stream) { lastRelease[parentId] = k }
    }

    private func releaseKey(_ s: StreamItem) -> String? {
        if let g = s.behaviorHints?.bingeGroup, !g.isEmpty { return g }
        return nil
    }
}
