import SwiftUI

/// One person using the app. Each profile has its own add-ons, library, progress, settings and Trakt link,
/// like profiles on the Android TV app. Profile 1 is the main profile.
struct Profile: Codable, Identifiable, Hashable {
    var id: Int
    var name: String
    var colorHex: String
    /// Optional four digit PIN asked for before the profile opens. Kept on this device only.
    var pin: String? = nil
    /// Uses the main profile's add-ons instead of its own (matches the Android setting of the same name).
    var usesPrimaryAddons: Bool = false

    var color: Color { Color(hex: colorHex) ?? .purple }
    var initial: String { String(name.trimmingCharacters(in: .whitespaces).first ?? "M").uppercased() }
}

/// Where the active profile's data lives. Profile 1 keeps the app's original storage, so nothing moves when
/// someone updates from a version without profiles.
enum ProfileScope {
    static var activeId = 1

    static func defaults(for id: Int) -> UserDefaults {
        id == 1 ? .standard : (UserDefaults(suiteName: "com.meowio.profile.\(id)") ?? .standard)
    }

    static var defaults: UserDefaults { defaults(for: activeId) }
}

@MainActor
final class ProfileStore: ObservableObject {
    static let palette = ["#8E5CF0", "#1E88E5", "#26A69A", "#F4B400", "#EF5350", "#EC407A", "#7E57C2", "#FF7043"]
    static let maxProfiles = 8

    @Published private(set) var profiles: [Profile]
    /// The profile in use, or nil while the "Who's watching?" screen is up.
    @Published private(set) var activeId: Int?
    @Published var askOnLaunch: Bool { didSet { UserDefaults.standard.set(askOnLaunch, forKey: "profilesAskOnLaunch") } }

    init() {
        let d = UserDefaults.standard
        var list: [Profile] = []
        if let data = d.data(forKey: "profiles"), let saved = try? JSONDecoder().decode([Profile].self, from: data) {
            list = saved
        }
        if !list.contains(where: { $0.id == 1 }) {
            list.insert(Profile(id: 1, name: "Main", colorHex: Self.palette[0]), at: 0)
        }
        profiles = list.sorted { $0.id < $1.id }
        askOnLaunch = d.object(forKey: "profilesAskOnLaunch") as? Bool ?? true
        let last = d.object(forKey: "activeProfile") as? Int ?? 1
        let lastExists = list.contains { $0.id == last }
        let lastLocked = list.first { $0.id == last }?.pin != nil
        if list.count > 1 && (askOnLaunch || lastLocked) {
            activeId = nil
        } else {
            activeId = lastExists ? last : 1
        }
        ProfileScope.activeId = activeId ?? 1
    }

    var active: Profile? { profiles.first { $0.id == activeId } }

    private func save() {
        if let data = try? JSONEncoder().encode(profiles) { UserDefaults.standard.set(data, forKey: "profiles") }
    }

    func select(_ id: Int) {
        guard profiles.contains(where: { $0.id == id }) else { return }
        ProfileScope.activeId = id
        UserDefaults.standard.set(id, forKey: "activeProfile")
        activeId = id
    }

    /// Back to the "Who's watching?" screen.
    func showPicker() { activeId = nil }

    @discardableResult
    func add(name: String, colorHex: String? = nil) -> Profile? {
        guard profiles.count < Self.maxProfiles else { return nil }
        let id = (profiles.map(\.id).max() ?? 1) + 1
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        let p = Profile(id: id, name: trimmed.isEmpty ? "Profile \(id)" : trimmed,
                        colorHex: colorHex ?? Self.palette[(id - 1) % Self.palette.count])
        profiles.append(p)
        save()
        return p
    }

    func update(_ profile: Profile) {
        guard let i = profiles.firstIndex(where: { $0.id == profile.id }) else { return }
        profiles[i] = profile
        save()
    }

    /// Deletes a profile and everything stored for it. The main profile cannot be removed.
    func remove(_ id: Int) {
        guard id != 1 else { return }
        profiles.removeAll { $0.id == id }
        UserDefaults.standard.removePersistentDomain(forName: "com.meowio.profile.\(id)")
        save()
        if activeId == id { select(1) }
    }

    /// Takes the account's profile list (names, colours, shared add-ons) while keeping this device's PINs.
    func adopt(remote: [Profile]) {
        guard !remote.isEmpty else { return }
        var merged: [Profile] = []
        for r in remote.sorted(by: { $0.id < $1.id }) {
            var p = r
            p.pin = profiles.first { $0.id == r.id }?.pin
            merged.append(p)
        }
        if !merged.contains(where: { $0.id == 1 }), let main = profiles.first(where: { $0.id == 1 }) {
            merged.insert(main, at: 0)
        }
        // Profiles made on this device that the account does not know yet stay too.
        for local in profiles where !merged.contains(where: { $0.id == local.id }) { merged.append(local) }
        profiles = merged.sorted { $0.id < $1.id }
        save()
    }
}

extension Color {
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else { return nil }
        let rgb = s.count == 8 ? v & 0xFFFFFF : v
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}

// MARK: - Views

struct ProfileAvatar: View {
    let profile: Profile
    var size: CGFloat = 160

    var body: some View {
        ZStack {
            Circle().fill(profile.color.gradient)
            Text(profile.initial).font(.system(size: size * 0.45, weight: .bold)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .overlay(alignment: .bottomTrailing) {
            if profile.pin != nil {
                Image(systemName: "lock.fill").font(.system(size: size * 0.16)).padding(size * 0.06)
                    .background(.ultraThinMaterial, in: Circle())
            }
        }
    }
}

/// "Who's watching?" Shown at launch when there is more than one profile, like the Android TV app.
struct ProfilePickerView: View {
    @EnvironmentObject var profiles: ProfileStore
    @State private var locked: Profile?
    @State private var adding = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.12, green: 0.07, blue: 0.2), .black], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 60) {
                Text("Who's watching?").font(.system(size: 56, weight: .bold))
                HStack(spacing: 50) {
                    ForEach(profiles.profiles) { p in
                        Button { open(p) } label: {
                            VStack(spacing: 20) {
                                ProfileAvatar(profile: p)
                                Text(p.name).font(.headline).lineLimit(1).frame(maxWidth: 200)
                            }
                            .padding(20)
                        }
                        .buttonStyle(.card)
                    }
                    if profiles.profiles.count < ProfileStore.maxProfiles {
                        Button { adding = true } label: {
                            VStack(spacing: 20) {
                                ZStack {
                                    Circle().fill(.ultraThinMaterial)
                                    Image(systemName: "plus").font(.system(size: 60, weight: .semibold))
                                }
                                .frame(width: 160, height: 160)
                                Text("Add profile").font(.headline)
                            }
                            .padding(20)
                        }
                        .buttonStyle(.card)
                    }
                }
            }
        }
        .sheet(item: $locked) { p in
            PinEntryView(title: "Enter the PIN for \(p.name)") { entered in
                if entered == p.pin {
                    locked = nil
                    profiles.select(p.id)
                    return true
                }
                return false
            }
        }
        .sheet(isPresented: $adding) { ProfileEditView(profile: nil) }
    }

    private func open(_ p: Profile) {
        if p.pin != nil { locked = p } else { profiles.select(p.id) }
    }
}

struct PinEntryView: View {
    let title: String
    /// Returns true when the PIN was right.
    let check: (String) -> Bool
    @State private var pin = ""
    @State private var wrong = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 40) {
            Text(title).font(.title2.bold())
            SecureField("PIN", text: $pin)
                .keyboardType(.numberPad)
                .frame(width: 400)
                .onSubmit(submit)
            if wrong { Text("That PIN is not right.").foregroundStyle(.orange) }
            HStack(spacing: 30) {
                Button("Open", action: submit)
                Button("Cancel") { dismiss() }
            }
        }
        .padding(80)
    }

    private func submit() {
        if !check(pin) { wrong = true; pin = "" }
    }
}

/// Create or edit a profile: name, colour, PIN and whether it shares the main profile's add-ons.
struct ProfileEditView: View {
    @EnvironmentObject var profiles: ProfileStore
    @Environment(\.dismiss) private var dismiss
    let profile: Profile?
    @State private var name = ""
    @State private var color = ProfileStore.palette[1]
    @State private var pin = ""
    @State private var sharesAddons = false
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") { TextField("Name", text: $name) }
                Section("Colour") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 24) {
                            ForEach(ProfileStore.palette, id: \.self) { hex in
                                Button { color = hex } label: {
                                    Circle().fill(Color(hex: hex) ?? .purple).frame(width: 70, height: 70)
                                        .overlay { if hex == color { Image(systemName: "checkmark").font(.title3.bold()) } }
                                }
                                .buttonStyle(.card)
                            }
                        }
                        .padding(20)
                    }
                }
                Section("PIN (four digits, leave empty for none)") {
                    SecureField("PIN", text: $pin).keyboardType(.numberPad)
                }
                if profile?.id != 1 {
                    Section {
                        Toggle("Use the main profile's add-ons", isOn: $sharesAddons)
                    }
                }
                Section {
                    Button(profile == nil ? "Create profile" : "Save") { save() }
                    if let p = profile, p.id != 1 {
                        Button("Delete profile", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(profile == nil ? "New profile" : "Edit profile")
        }
        .onAppear {
            if let p = profile {
                name = p.name
                color = p.colorHex
                pin = p.pin ?? ""
                sharesAddons = p.usesPrimaryAddons
            }
        }
        .confirmationDialog("Delete this profile and everything saved in it?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                if let p = profile { profiles.remove(p.id) }
                dismiss()
            }
        }
    }

    private func save() {
        let digits = pin.filter(\.isNumber)
        let cleanPin: String? = digits.count == 4 ? digits : nil
        if var p = profile {
            p.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? p.name : name.trimmingCharacters(in: .whitespaces)
            p.colorHex = color
            p.pin = cleanPin
            p.usesPrimaryAddons = p.id != 1 && sharesAddons
            profiles.update(p)
        } else if var p = profiles.add(name: name, colorHex: color) {
            p.pin = cleanPin
            p.usesPrimaryAddons = sharesAddons
            profiles.update(p)
        }
        dismiss()
    }
}

/// Settings page listing profiles.
struct ProfilesSettingsView: View {
    @EnvironmentObject var profiles: ProfileStore
    @State private var editing: Profile?
    @State private var adding = false

    var body: some View {
        List {
            Section {
                ForEach(profiles.profiles) { p in
                    Button { editing = p } label: {
                        HStack(spacing: 24) {
                            ProfileAvatar(profile: p, size: 60)
                            Text(p.name)
                            Spacer()
                            if p.id == profiles.activeId { Text("In use").foregroundStyle(.secondary) }
                        }
                    }
                }
                if profiles.profiles.count < ProfileStore.maxProfiles {
                    Button("Add profile") { adding = true }
                }
            }
            Section {
                Toggle("Ask who's watching when Meowio opens", isOn: $profiles.askOnLaunch)
                Button("Switch profile") { profiles.showPicker() }
            }
        }
        .navigationTitle("Profiles")
        .sheet(item: $editing) { ProfileEditView(profile: $0) }
        .sheet(isPresented: $adding) { ProfileEditView(profile: nil) }
    }
}
