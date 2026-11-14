package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Serves a small page on the home network where a phone or computer can download this TV's
 * settings backup or send one back. A TV has no usable file picker, so this stands in for one.
 *
 * Downloading and restoring need the [pin] shown on the TV (the QR code carries it), so other
 * devices on the network cannot read or replace the settings. Too many wrong PINs lock the page
 * until it is opened again on the TV.
 */
class SettingsBackupServer(
    private val context: Context,
    private val exportBackup: (includePrivateAddonLinks: Boolean) -> String?,
    private val importBackup: (String) -> Boolean,
    private val logoProvider: () -> ByteArray?,
    port: Int,
    val pin: String
) : NanoHTTPD(port) {

    @Volatile
    private var failedPinAttempts = 0

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" ->
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
        session.method == Method.GET && session.uri == "/logo.png" -> serveLogo()
        session.method == Method.GET && session.uri == "/$FILE_NAME" ->
            withPin(session) { serveBackup(includePrivateAddonLinks = session.parms["private"] == "1") }
        session.method == Method.POST && session.uri == "/api/restore" -> withPin(session) { handleRestore(session) }
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun withPin(session: IHTTPSession, block: () -> Response): Response {
        if (failedPinAttempts >= MAX_PIN_ATTEMPTS) return json(Response.Status.FORBIDDEN, "locked")
        val given = session.headers["x-backup-pin"] ?: session.parms["pin"].orEmpty()
        val matches = MessageDigest.isEqual(given.trim().toByteArray(), pin.toByteArray())
        if (!matches) {
            failedPinAttempts++
            return json(Response.Status.UNAUTHORIZED, "pin")
        }
        return block()
    }

    private fun serveLogo(): Response {
        val bytes = logoProvider() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        return newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun serveBackup(includePrivateAddonLinks: Boolean): Response {
        val json = exportBackup(includePrivateAddonLinks)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Backup failed")
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$FILE_NAME\"")
        }
    }

    private fun handleRestore(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length <= 0 || length > MAX_BYTES) return json(Response.Status.BAD_REQUEST, "invalid")
        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = session.inputStream.read(bytes, offset, bytes.size - offset)
            if (read <= 0) break
            offset += read
        }
        if (offset != bytes.size || !importBackup(String(bytes, Charsets.UTF_8))) {
            return json(Response.Status.BAD_REQUEST, "invalid")
        }
        return json(Response.Status.OK, "restored")
    }

    private fun json(status: Response.Status, state: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", """{"status":"$state"}""")

    private fun page(): String {
        val strings = context.withAppLocale()
        val appName = strings.getString(R.string.app_name).html()
        val title = strings.getString(R.string.backup_title).html()
        val intro = strings.getString(R.string.backup_web_intro).html()
        val download = strings.getString(R.string.backup_web_download).html()
        val restore = strings.getString(R.string.backup_web_restore).html()
        val pinLabel = strings.getString(R.string.backup_web_pin_label).html()
        val privateDownload = strings.getString(R.string.backup_web_private_download).html()
        val privateWarning = strings.getString(R.string.backup_web_private_warning).html()
        val wrongPin = strings.getString(R.string.backup_web_pin_wrong).js()
        val locked = strings.getString(R.string.backup_web_locked).js()
        val restored = strings.getString(R.string.backup_restored).js()
        val failed = strings.getString(R.string.backup_restore_failed).js()
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>$appName - $title</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #000; color: #fff; min-height: 100vh; line-height: 1.5; }
  .page { max-width: 600px; margin: 0 auto; padding: 0 1.5rem 4rem; }
  .header { text-align: center; padding: 3rem 0 2rem; border-bottom: 1px solid rgba(255,255,255,0.08); margin-bottom: 2rem; }
  .header img { height: 40px; margin-bottom: 0.5rem; }
  .header p { color: rgba(255,255,255,0.5); font-size: 0.9rem; }
  .intro { color: rgba(255,255,255,0.55); font-size: 0.9rem; margin-bottom: 1.5rem; }
  .btn { display: block; width: 100%; text-align: center; text-decoration: none; background: transparent; border: 1px solid rgba(255,255,255,0.25); border-radius: 100px; padding: 0.9rem 1.3rem; color: #fff; font: inherit; font-size: 0.9rem; cursor: pointer; margin-bottom: 0.75rem; }
  .btn:hover { background: #fff; color: #000; }
  input[type=file] { display: none; }
  .status { color: rgba(135,239,172,0.95); font-size: 0.9rem; min-height: 24px; margin-top: 1rem; text-align: center; }
  .status.error { color: rgba(207,102,121,0.95); }
  .pin { display: block; width: 100%; background: transparent; border: 1px solid rgba(255,255,255,0.25); border-radius: 12px; padding: 0.8rem 1rem; color: #fff; font: inherit; font-size: 1.2rem; letter-spacing: 0.3em; text-align: center; margin-bottom: 1.25rem; }
  .label { color: rgba(255,255,255,0.55); font-size: 0.85rem; margin-bottom: 0.4rem; }
  .warn { color: rgba(255,200,120,0.9); font-size: 0.8rem; margin: 1.5rem 0 0.5rem; }
  .btn.small { font-size: 0.8rem; padding: 0.6rem 1rem; border-color: rgba(255,200,120,0.4); }
</style>
</head>
<body>
<div class="page">
  <div class="header"><img src="/logo.png" alt="$appName"><p>$title</p></div>
  <div class="intro">$intro</div>
  <div class="label">$pinLabel</div>
  <input id="pin" class="pin" inputmode="numeric" autocomplete="off" maxlength="6">
  <button class="btn" id="download">$download</button>
  <label class="btn" for="file">$restore</label>
  <input id="file" type="file" accept=".json,application/json">
  <div class="status" id="status"></div>
  <div class="warn">$privateWarning</div>
  <button class="btn small" id="downloadPrivate">$privateDownload</button>
</div>
<script>
const input=document.getElementById('file');
const box=document.getElementById('status');
const pinBox=document.getElementById('pin');
const fromLink=new URLSearchParams(location.hash.slice(1)).get('pin');
if(fromLink){pinBox.value=fromLink}
function show(text,bad){box.textContent=text;box.className=bad?'status error':'status'}
function failure(response){show(response.status===403?$locked:(response.status===401?$wrongPin:$failed),true)}
async function download(withPrivate){
  try{
    const response=await fetch('/$FILE_NAME'+(withPrivate?'?private=1':''),{headers:{'X-Backup-Pin':pinBox.value.trim()}});
    if(!response.ok){failure(response);return}
    const url=URL.createObjectURL(await response.blob());
    const link=document.createElement('a');
    link.href=url;link.download='$FILE_NAME';document.body.appendChild(link);link.click();link.remove();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
    show('',false);
  }catch(e){show($failed,true)}
}
document.getElementById('download').addEventListener('click',()=>download(false));
document.getElementById('downloadPrivate').addEventListener('click',()=>download(true));
input.addEventListener('change',async()=>{
  const file=input.files[0];
  if(!file)return;
  try{
    const response=await fetch('/api/restore',{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Backup-Pin':pinBox.value.trim()},body:file});
    if(response.ok){show($restored,false)}else{failure(response)}
  }catch(e){show($failed,true)}
  input.value='';
});
</script>
</body>
</html>
""".trimIndent()
    }

    private fun String.html(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun String.js(): String = buildString {
        append('"')
        this@js.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '<' -> append("\\u003c")
                else -> append(char)
            }
        }
        append('"')
    }

    companion object {
        const val FILE_NAME = "meowio-tv-backup.json"
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_PIN_ATTEMPTS = 10

        /** A fresh six-digit PIN for one session of the backup page. */
        fun newPin(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()

        fun startOnAvailablePort(
            context: Context,
            exportBackup: (includePrivateAddonLinks: Boolean) -> String?,
            importBackup: (String) -> Boolean,
            logoProvider: () -> ByteArray?,
            startPort: Int = 8096,
            maxAttempts: Int = 10,
            pin: String = newPin()
        ): SettingsBackupServer? {
            for (port in startPort until startPort + maxAttempts) {
                val server = SettingsBackupServer(context, exportBackup, importBackup, logoProvider, port, pin)
                if (runCatching { server.start(SOCKET_READ_TIMEOUT, false) }.isSuccess) return server
            }
            return null
        }
    }
}
