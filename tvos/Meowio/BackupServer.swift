import Foundation
import Network
import SwiftUI

/// Apple TV has no file picker, so backup and restore run through a small web page on the local network.
/// Open the address shown on the TV from a phone or computer on the same Wi-Fi to download a backup file
/// or upload one. The server only runs while the Backup screen is open.
/// Downloading and restoring need the PIN shown on the TV, so other devices on the network cannot read
/// or replace the settings. Too many wrong PINs lock the page until the screen is opened again.
@MainActor
final class BackupServer: ObservableObject {
    @Published private(set) var address: String?
    @Published private(set) var pin = ""
    @Published var lastEvent: String?
    private var failedPinAttempts = 0
    private let maxPinAttempts = 10

    private var listener: NWListener?
    private let port: UInt16 = 8765
    private let queue = DispatchQueue(label: "meowio.backup.server")
    var exportBackup: ((_ includePrivateAddonLinks: Bool) -> String)?
    var restoreBackup: ((String) -> Bool)?

    func start() {
        guard listener == nil else { return }
        pin = String(Int.random(in: 100_000...999_999))
        failedPinAttempts = 0
        guard let nwPort = NWEndpoint.Port(rawValue: port), let l = try? NWListener(using: .tcp, on: nwPort) else {
            lastEvent = "Could not start the backup page."
            return
        }
        l.newConnectionHandler = { [weak self] conn in
            Task { @MainActor in self?.handle(conn) }
        }
        l.start(queue: queue)
        listener = l
        address = Self.localIPv4().map { "http://\($0):\(port)" }
        if address == nil { lastEvent = "Connect the Apple TV to your network to use the backup page." }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        address = nil
        pin = ""
    }

    private func pinMatches(_ req: HTTPRequest) -> Bool {
        let given = (req.headers["x-backup-pin"] ?? req.query["pin"] ?? "").trimmingCharacters(in: .whitespaces)
        if !pin.isEmpty && given == pin { return true }
        failedPinAttempts += 1
        return false
    }

    // MARK: HTTP

    private func handle(_ conn: NWConnection) {
        conn.start(queue: queue)
        receive(conn, buffer: Data())
    }

    private nonisolated func receive(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, done, error in
            guard let self else { conn.cancel(); return }
            var buf = buffer
            if let data { buf.append(data) }
            if error != nil || buf.count > 4 * 1024 * 1024 { conn.cancel(); return }
            if let request = HTTPRequest(buf) {
                Task { @MainActor in self.respond(conn, request) }
            } else if done {
                conn.cancel()
            } else {
                self.receive(conn, buffer: buf)
            }
        }
    }

    private func respond(_ conn: NWConnection, _ req: HTTPRequest) {
        let needsPin = req.path == "/meowio-backup.json" || req.path == "/restore"
        if needsPin && failedPinAttempts >= maxPinAttempts {
            send(conn, status: "403 Forbidden", type: "text/plain; charset=utf-8",
                 body: Data("Too many wrong PINs. Close and reopen Backup on the Apple TV to try again.".utf8))
            return
        }
        if needsPin && !pinMatches(req) {
            send(conn, status: "401 Unauthorized", type: "text/plain; charset=utf-8",
                 body: Data("That PIN does not match the one on your Apple TV.".utf8))
            return
        }
        switch (req.method, req.path) {
        case ("GET", "/"), ("GET", "/index.html"):
            send(conn, status: "200 OK", type: "text/html; charset=utf-8", body: Data(Self.page.utf8))
        case ("GET", "/meowio-backup.json"):
            let text = exportBackup?(req.query["private"] == "1") ?? ""
            lastEvent = "Backup downloaded."
            send(conn, status: "200 OK", type: "application/json",
                 body: Data(text.utf8), extra: ["Content-Disposition": "attachment; filename=\"meowio-backup.json\""])
        case ("POST", "/restore"):
            let text = String(data: req.body, encoding: .utf8) ?? ""
            let ok = restoreBackup?(text) ?? false
            lastEvent = ok ? "Settings restored from your backup." : "That file is not a Meowio backup."
            send(conn, status: ok ? "200 OK" : "400 Bad Request", type: "text/plain; charset=utf-8",
                 body: Data((ok ? "Restored. Your Apple TV is using the backed-up settings now." : "That file is not a Meowio backup.").utf8))
        default:
            send(conn, status: "404 Not Found", type: "text/plain", body: Data("Not found".utf8))
        }
    }

    private func send(_ conn: NWConnection, status: String, type: String, body: Data, extra: [String: String] = [:]) {
        var head = "HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n"
        for (k, v) in extra { head += "\(k): \(v)\r\n" }
        head += "\r\n"
        var out = Data(head.utf8)
        out.append(body)
        conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
    }

    private struct HTTPRequest {
        var method: String
        var path: String
        var query: [String: String] = [:]
        var headers: [String: String] = [:]
        var body: Data

        /// Parses a complete request, or returns nil while more bytes are needed.
        init?(_ data: Data) {
            guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
            guard let head = String(data: data[data.startIndex..<headerEnd.lowerBound], encoding: .utf8) else { return nil }
            let lines = head.components(separatedBy: "\r\n")
            let first = lines.first?.split(separator: " ") ?? []
            guard first.count >= 2 else { return nil }
            method = String(first[0])
            let target = first[1].split(separator: "?", maxSplits: 1)
            path = String(target.first ?? "/")
            if target.count == 2 {
                for pair in target[1].split(separator: "&") {
                    let kv = pair.split(separator: "=", maxSplits: 1)
                    let key = String(kv[0]).removingPercentEncoding ?? String(kv[0])
                    let value = kv.count == 2 ? (String(kv[1]).removingPercentEncoding ?? String(kv[1])) : ""
                    query[key] = value
                }
            }
            var length = 0
            for line in lines.dropFirst() {
                let parts = line.split(separator: ":", maxSplits: 1)
                guard parts.count == 2 else { continue }
                let name = parts[0].lowercased()
                let value = parts[1].trimmingCharacters(in: .whitespaces)
                headers[name] = value
                if name == "content-length" { length = Int(value) ?? 0 }
            }
            let bodyStart = headerEnd.upperBound
            guard data.count - bodyStart >= length else { return nil }
            body = data[bodyStart..<(bodyStart + length)]
        }
    }

    /// The Apple TV's own address on Wi-Fi or Ethernet.
    nonisolated static func localIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var fallback: String?
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: ifa.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("127.") || ip.hasPrefix("169.254.") { continue }
            if name.hasPrefix("en") { return ip }
            if fallback == nil { fallback = ip }
        }
        return fallback
    }

    private static let page = """
    <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>Meowio backup</title>
    <style>
    body{font-family:-apple-system,system-ui,sans-serif;background:#120d1c;color:#eee;margin:0;padding:24px;max-width:560px}
    h1{color:#b073f2;margin-top:0}a.btn,button{display:inline-block;background:#b073f2;color:#120d1c;border:0;border-radius:12px;
    padding:14px 20px;font-size:17px;font-weight:600;text-decoration:none;cursor:pointer}
    .card{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14);border-radius:18px;padding:20px;margin:18px 0}
    #msg,#dmsg{margin-top:14px;min-height:1.4em}.warn{color:#ffc878;font-size:14px;margin-top:18px}
    </style></head><body>
    <h1>Meowio for Apple TV</h1>
    <div class="card"><h2>PIN</h2><p>Enter the PIN shown on your Apple TV.</p>
    <input id="pin" inputmode="numeric" autocomplete="off" maxlength="6" style="font-size:22px;letter-spacing:.3em;padding:10px;width:9em"></div>
    <div class="card"><h2>Back up</h2><p>Saves your add-ons, playback settings, theme, pinned sources and Live TV source.
    Your AI key is never included, and add-on links that contain your own settings (they often include a debrid key) are left out.</p>
    <button onclick="down(false)">Download backup</button>
    <p class="warn">Need those add-on links too? Only use this if you keep the file somewhere private.</p>
    <button onclick="down(true)">Download with private add-on links</button><div id="dmsg"></div></div>
    <div class="card"><h2>Restore</h2><p>Choose a Meowio backup file. The Apple TV switches to its settings straight away.</p>
    <input type="file" id="file" accept=".json,application/json"><br><br><button onclick="up()">Restore to Apple TV</button>
    <div id="msg"></div></div>
    <script>
    function pin(){return document.getElementById('pin').value.trim()}
    async function down(p){const m=document.getElementById('dmsg');
    try{const r=await fetch('/meowio-backup.json'+(p?'?private=1':''),{headers:{'X-Backup-Pin':pin()}});
    if(!r.ok){m.textContent=await r.text();return}
    const u=URL.createObjectURL(await r.blob());const a=document.createElement('a');a.href=u;a.download='meowio-backup.json';
    document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),1000);m.textContent=''}
    catch(e){m.textContent='Could not reach the Apple TV. Keep the Backup screen open on the TV.'}}
    async function up(){const f=document.getElementById('file').files[0];const m=document.getElementById('msg');
    if(!f){m.textContent='Choose a backup file first.';return}
    try{const r=await fetch('/restore',{method:'POST',headers:{'X-Backup-Pin':pin()},body:await f.text()});m.textContent=await r.text()}
    catch(e){m.textContent='Could not reach the Apple TV. Keep the Backup screen open on the TV.'}}
    </script></body></html>
    """
}

/// Settings screen for backup and restore: the web page address, plus the old paste-text fallback.
struct BackupView: View {
    @EnvironmentObject var settings: SettingsStore
    @StateObject private var server = BackupServer()
    @State private var restoreText = ""
    @State private var restoreMessage: String?

    var body: some View {
        List {
            Section("From a phone or computer") {
                if let address = server.address {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("On a phone or computer on the same network, open:")
                        Text(address).font(.system(size: 44, weight: .bold, design: .monospaced))
                            .foregroundStyle(settings.theme.accent)
                        Text("PIN \(server.pin)").font(.system(size: 36, weight: .semibold, design: .monospaced))
                        Text("Download a backup file there, or upload one to restore it here. Keep this screen open while you do.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 10)
                } else {
                    Text("Starting…")
                }
                if let event = server.lastEvent { Text(event).foregroundStyle(.secondary) }
            }
            Section("Paste instead") {
                TextField("Paste backup text to restore", text: $restoreText)
                Button("Restore from text") {
                    restoreMessage = settings.restoreBackup(restoreText) ? "Settings restored." : "That is not a Meowio backup."
                }
                if let restoreMessage { Text(restoreMessage).foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Backup and restore")
        .onAppear {
            let store = settings
            server.exportBackup = { includePrivate in store.exportBackup(includePrivateAddonLinks: includePrivate) }
            server.restoreBackup = { text in store.restoreBackup(text) }
            server.start()
        }
        .onDisappear { server.stop() }
    }
}
