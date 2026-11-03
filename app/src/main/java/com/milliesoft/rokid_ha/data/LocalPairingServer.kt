package com.milliesoft.rokid_ha.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Lightweight embedded HTTP server running on port 8765 on the Rokid Glasses.
 * Allows the user to:
 * 1. Open `http://<glasses-ip>:8765` in any phone/PC browser to paste their Home Assistant URL & Token, OR
 * 2. Click "Send to Glasses" from `tools/qr-config-generator.html`, OR
 * 3. Scan a tiny QR code pointing to the config.
 */
class LocalPairingServer(
    val port: Int = 8765,
    private val currentConfigProvider: () -> HaConfig,
    private val onConfigReceived: (String) -> Unit
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "RokidLocalPairingServer", isDaemon = true) {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (isRunning && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread(isDaemon = true) {
                        handleClient(client)
                    }
                }
            } catch (_: Exception) {
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            while (true) {
                val header = reader.readLine()
                if (header.isNullOrEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "OPTIONS") {
                sendResponse(client, "200 OK", "text/plain", "")
                return
            }

            if (method == "POST" && path.startsWith("/config")) {
                val bodyChars = CharArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val r = reader.read(bodyChars, totalRead, contentLength - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                val body = String(bodyChars, 0, totalRead)
                onConfigReceived(body)
                sendResponse(
                    client,
                    "200 OK",
                    "application/json",
                    """{"status":"ok","message":"Configuration applied to Rokid Glasses!"}"""
                )
                return
            }

            // Serve built-in pairing web page for GET /
            val cfg = currentConfigProvider()
            val html = buildPairingWebPage(cfg)
            sendResponse(client, "200 OK", "text/html; charset=utf-8", html)
        }
    }

    private fun sendResponse(
        client: Socket,
        status: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun buildPairingWebPage(cfg: HaConfig): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>Rokid Glasses — Home Assistant Pairing</title>
          <style>
            body { background:#080c09; color:#e2f5e8; font-family:monospace; padding:24px; max-width:640px; margin:0 auto; }
            .card { background:#0e1610; border:2px solid #00ff66; border-radius:10px; padding:20px; }
            h2 { color:#00ff66; margin-top:0; }
            label { display:block; color:#00b848; margin-top:14px; margin-bottom:6px; }
            input, textarea { width:100%; box-sizing:border-box; background:#030504; color:#fff; border:1px solid #005522; border-radius:6px; padding:10px; font-family:monospace; }
            button { margin-top:18px; width:100%; padding:14px; background:#00ff66; color:#000; border:none; border-radius:6px; font-weight:bold; font-family:monospace; font-size:16px; cursor:pointer; }
            #status { margin-top:14px; color:#00ff66; font-weight:bold; }
          </style>
        </head>
        <body>
          <div class="card">
            <h2>ROKID GLASSES // HOME ASSISTANT SETUP</h2>
            <p>Send your Home Assistant URL and Long-Lived Access Token directly to your glasses over Wi-Fi:</p>
            <label>HOME ASSISTANT URL (use IP address, e.g. http://192.168.1.50:8123)</label>
            <input id="url" value="${cfg.haUrl}" />
            <label>LONG-LIVED ACCESS TOKEN</label>
            <textarea id="token" rows="4" placeholder="Paste your Home Assistant Long-Lived Access Token here">${cfg.accessToken}</textarea>
            <label>OPTIONAL PINNED ENTITIES (comma-separated, or leave blank for all)</label>
            <input id="entities" value="${cfg.pinnedEntities.joinToString(", ")}" placeholder="climate.living_room, light.kitchen" />
            <button onclick="save()">SAVE TO ROKID GLASSES NOW</button>
            <div id="status"></div>
          </div>
          <script>
            async function save() {
              const url = document.getElementById('url').value.trim();
              const token = document.getElementById('token').value.trim();
              const rawEnt = document.getElementById('entities').value.trim();
              const entities = rawEnt ? rawEnt.split(',').map(s => s.trim()).filter(Boolean) : [];
              const res = await fetch('/config', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ url, token, entities, show_pinned_only: entities.length > 0 })
              });
              if (res.ok) {
                document.getElementById('status').textContent = '✓ SAVED! Check your Rokid Glasses HUD!';
              } else {
                document.getElementById('status').textContent = 'Error saving configuration.';
              }
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        fun getLocalWifiIpAddress(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            if (!ip.startsWith("169.254.")) return ip
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}
