/// Values filled in by the Apple build workflow from repository secrets. Empty in the repository on purpose:
/// with no account server the Account page explains it is off, and Trakt asks for the user's own app keys.
enum BuildSecrets {
    static let supabaseURL = ""
    static let supabaseAnonKey = ""
    static let traktClientId = ""
    static let traktClientSecret = ""
}
