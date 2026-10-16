package com.example

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

data class ReceivedFile(
    val name: String,
    val file: File,
    val size: Long
)

class ServerManager(private val context: Context) {
    private var server: NettyApplicationEngine? = null
    var currentPort: Int = 8080
        private set

    private val _receivedFiles = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val receivedFiles: StateFlow<List<ReceivedFile>> = _receivedFiles.asStateFlow()

    val playRequests = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val sharedClipboard = MutableStateFlow("")

    val uploadDir: File by lazy {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "DeskDrop")
        dir.apply { mkdirs() }
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val serviceName = "DeskDrop"

    init {
        refreshFiles()
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager?
    }

    fun updateClipboard(text: String) {
        sharedClipboard.value = text
    }

    fun addFilesFromUris(uris: List<Uri>) {
        for (uri in uris) {
            try {
                var displayName = "shared_${System.currentTimeMillis()}"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
                val targetFile = File(uploadDir, displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.e("ServerManager", "Error adding file from uri: $uri", e)
            }
        }
        refreshFiles()
    }

    private fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@ServerManager.serviceName
            serviceType = "_http._tcp."
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("ServerManager", "NSD Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("ServerManager", "NSD Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("ServerManager", "NSD Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("ServerManager", "NSD Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("ServerManager", "Failed to register NSD service", e)
        }
    }

    private fun unregisterService() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e("ServerManager", "Failed to unregister NSD service", e)
        } finally {
            registrationListener = null
        }
    }

    fun refreshFiles() {
        val files = uploadDir.listFiles()?.filter { it.isFile }?.filter { file ->
            if (file.name.endsWith(".part")) {
                if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000) {
                    file.delete()
                }
                false
            } else {
                true
            }
        }?.map {
            ReceivedFile(it.name, it, it.length())
        }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
        _receivedFiles.value = files
    }

    fun getFreeSpace(): Long {
        return try {
            val stat = android.os.StatFs(uploadDir.path)
            stat.availableBytes
        } catch (e: Exception) {
            uploadDir.freeSpace
        }
    }

    fun getStorageStats(): Pair<Long, Long> {
        return try {
            val stat = android.os.StatFs(uploadDir.path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            Pair(free, total)
        } catch (e: Exception) {
            Pair(uploadDir.freeSpace, uploadDir.totalSpace)
        }
    }

    fun startServer(port: Int = 8080) {
        if (server != null) return
        
        var currentPort = port
        var started = false
        var attempts = 0
        
        while (!started && attempts < 5) {
            try {
                server = embeddedServer(Netty, port = currentPort) {
                    install(PartialContent)
                    install(AutoHeadResponse)
                    routing {
                        get("/") {
                            val files = _receivedFiles.value
                            val freeSpace = uploadDir.freeSpace
                            val formattedFreeSpace = formatFileSize(freeSpace)

                            val filesListJson = files.map { file ->
                                val ext = file.name.substringAfterLast('.', "").lowercase()
                                val isVideo = ext in listOf("mp4", "webm", "mkv", "avi", "mov")
                                val isAudio = ext in listOf("mp3", "wav", "m4a", "flac", "aac", "ogg")
                                val isImage = ext in listOf("jpg", "jpeg", "png", "gif", "webp")
                                val isApk = ext == "apk"
                                val type = when {
                                    isVideo -> "video"
                                    isAudio -> "audio"
                                    isImage -> "image"
                                    isApk -> "apk"
                                    else -> "file"
                                }
                                """{"name":"${file.name.replace("\"", "\\\"")}","size":"${formatFileSize(file.size)}","type":"$type","rawSize":${file.size}}"""
                            }.joinToString(",")

                            call.respondText(contentType = io.ktor.http.ContentType.Text.Html) {
                                """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>DeskDrop - Local File Transfer</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        :root {
            --bg: #0f172a;
            --surface: #1e293b;
            --surface-hover: #334155;
            --primary: #38bdf8;
            --primary-hover: #0ea5e9;
            --accent: #a855f7;
            --success: #10b981;
            --danger: #ef4444;
            --text: #f8fafc;
            --text-muted: #94a3b8;
            --border: #334155;
            --radius: 14px;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
            background: var(--bg);
            color: var(--text);
            padding: 24px 16px 80px;
            min-height: 100vh;
        }
        .container { max-width: 900px; margin: 0 auto; }
        
        /* Header */
        header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            flex-wrap: wrap;
            gap: 16px;
            padding-bottom: 24px;
            border-bottom: 1px solid var(--border);
            margin-bottom: 24px;
        }
        .logo-wrap { display: flex; align-items: center; gap: 12px; }
        .logo-icon {
            width: 44px;
            height: 44px;
            border-radius: 12px;
            background: linear-gradient(135deg, var(--primary), var(--accent));
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 22px;
            box-shadow: 0 4px 12px rgba(56, 189, 248, 0.3);
        }
        h1 { font-size: 1.6rem; font-weight: 700; letter-spacing: -0.5px; }
        .badge-live {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            font-size: 0.8rem;
            color: var(--success);
            background: rgba(16, 185, 129, 0.15);
            padding: 4px 10px;
            border-radius: 20px;
            font-weight: 600;
        }
        .badge-live::before {
            content: '';
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: var(--success);
            box-shadow: 0 0 8px var(--success);
        }
        .storage-pill {
            background: var(--surface);
            padding: 8px 16px;
            border-radius: 30px;
            font-size: 0.85rem;
            color: var(--text-muted);
            border: 1px solid var(--border);
        }
        .storage-pill span { color: var(--text); font-weight: 600; }

        /* Grid */
        .grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
            margin-bottom: 28px;
        }
        @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }

        /* Cards */
        .card {
            background: var(--surface);
            border-radius: var(--radius);
            padding: 20px;
            border: 1px solid var(--border);
            box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
        }
        .card-title {
            font-size: 1.1rem;
            font-weight: 600;
            margin-bottom: 14px;
            display: flex;
            align-items: center;
            gap: 8px;
        }

        /* Drag & Drop Upload Zone */
        .dropzone {
            border: 2px dashed #475569;
            border-radius: var(--radius);
            padding: 32px 20px;
            text-align: center;
            cursor: pointer;
            transition: all 0.2s ease;
            background: rgba(15, 23, 42, 0.4);
        }
        .dropzone:hover, .dropzone.dragover {
            border-color: var(--primary);
            background: rgba(56, 189, 248, 0.08);
            transform: scale(1.01);
        }
        .dropzone-icon { font-size: 40px; margin-bottom: 12px; }
        .dropzone-title { font-size: 1.05rem; font-weight: 600; margin-bottom: 6px; }
        .dropzone-sub { font-size: 0.85rem; color: var(--text-muted); }
        input[type=file] { display: none; }

        /* Upload Queue / Progress */
        #upload-status {
            display: none;
            margin-top: 14px;
            background: rgba(15, 23, 42, 0.6);
            padding: 12px;
            border-radius: 10px;
            border: 1px solid var(--border);
        }
        .progress-bar-bg {
            width: 100%;
            height: 8px;
            background: #334155;
            border-radius: 4px;
            overflow: hidden;
            margin: 8px 0;
        }
        .progress-bar-fill {
            width: 0%;
            height: 100%;
            background: linear-gradient(90deg, var(--primary), var(--accent));
            transition: width 0.15s ease;
        }
        .progress-info {
            display: flex;
            justify-content: space-between;
            font-size: 0.8rem;
            color: var(--text-muted);
        }

        /* Clipboard Card */
        .clipboard-box {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        textarea {
            width: 100%;
            height: 80px;
            background: #0f172a;
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 10px;
            color: var(--text);
            font-family: inherit;
            font-size: 0.9rem;
            resize: none;
        }
        textarea:focus { outline: none; border-color: var(--primary); }
        .btn {
            background: var(--primary);
            color: #0f172a;
            border: none;
            border-radius: 8px;
            padding: 10px 16px;
            font-weight: 600;
            font-size: 0.9rem;
            cursor: pointer;
            transition: all 0.15s ease;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
        }
        .btn:hover { background: var(--primary-hover); transform: translateY(-1px); }
        .btn-secondary {
            background: var(--surface-hover);
            color: var(--text);
        }
        .btn-secondary:hover { background: #475569; }
        .btn-danger {
            background: rgba(239, 68, 68, 0.2);
            color: #fca5a5;
        }
        .btn-danger:hover { background: rgba(239, 68, 68, 0.4); }
        .btn-small { padding: 6px 10px; font-size: 0.8rem; border-radius: 6px; }

        /* Filter & Search Bar */
        .search-row {
            display: flex;
            gap: 12px;
            flex-wrap: wrap;
            margin-bottom: 16px;
            align-items: center;
            justify-content: space-between;
        }
        .search-input {
            flex: 1;
            min-width: 200px;
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 10px 14px;
            color: var(--text);
            font-size: 0.9rem;
        }
        .search-input:focus { outline: none; border-color: var(--primary); }
        .filter-tabs { display: flex; gap: 8px; flex-wrap: wrap; }
        .filter-tab {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 20px;
            padding: 6px 14px;
            font-size: 0.85rem;
            color: var(--text-muted);
            cursor: pointer;
        }
        .filter-tab.active {
            background: var(--primary);
            color: #0f172a;
            font-weight: 600;
            border-color: var(--primary);
        }

        /* Files List */
        .file-list {
            display: flex;
            flex-direction: column;
            gap: 10px;
        }
        .file-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 14px 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 14px;
            transition: background 0.15s ease;
        }
        .file-card:hover { background: var(--surface-hover); }
        .file-meta {
            display: flex;
            align-items: center;
            gap: 14px;
            min-width: 0;
            flex: 1;
        }
        .file-badge {
            width: 40px;
            height: 40px;
            border-radius: 10px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 18px;
            flex-shrink: 0;
        }
        .badge-video { background: rgba(168, 85, 247, 0.2); color: #c084fc; }
        .badge-audio { background: rgba(16, 185, 129, 0.2); color: #6ee7b7; }
        .badge-image { background: rgba(56, 189, 248, 0.2); color: #7dd3fc; }
        .badge-apk { background: rgba(245, 158, 11, 0.2); color: #fcd34d; }
        .badge-file { background: rgba(148, 163, 184, 0.2); color: #cbd5e1; }
        .file-info { min-width: 0; }
        .file-name {
            font-weight: 600;
            font-size: 0.95rem;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            display: block;
        }
        .file-sub { font-size: 0.8rem; color: var(--text-muted); margin-top: 2px; }
        .file-actions { display: flex; gap: 8px; flex-shrink: 0; align-items: center; }

        /* Media Player Drawer */
        .media-drawer {
            display: none;
            margin-top: 10px;
            background: #0f172a;
            padding: 12px;
            border-radius: 8px;
            border: 1px solid var(--border);
        }
        video, audio { width: 100%; border-radius: 6px; outline: none; }

        /* Toast */
        #toast {
            position: fixed;
            bottom: 24px;
            right: 24px;
            background: #1e293b;
            color: #f8fafc;
            padding: 12px 20px;
            border-radius: 10px;
            box-shadow: 0 10px 25px rgba(0,0,0,0.5);
            border: 1px solid var(--primary);
            font-size: 0.9rem;
            display: none;
            z-index: 1000;
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="logo-wrap">
                <div class="logo-icon">⚡</div>
                <div>
                    <h1>DeskDrop</h1>
                    <div class="badge-live">Direct Local Transfer Active</div>
                </div>
            </div>
            <div class="storage-pill">Available Phone Storage: <span>$formattedFreeSpace</span></div>
        </header>

        <div class="grid">
            <!-- Upload Box -->
            <div class="card">
                <div class="card-title"><span>📤</span> Send Files to Mobile</div>
                <div class="dropzone" id="dropzone" onclick="document.getElementById('file-input').click()">
                    <div class="dropzone-icon">📁</div>
                    <div class="dropzone-title">Drop files here or click to browse</div>
                    <div class="dropzone-sub">Videos, Music, Photos, APKs, Docs & Folders</div>
                    <input type="file" id="file-input" multiple onchange="handleFiles(this.files)">
                </div>
                <div id="upload-status">
                    <div style="display:flex; justify-content:space-between; font-weight:600; font-size:0.85rem;">
                        <span id="upload-file-name">Uploading...</span>
                        <span id="upload-speed">0 MB/s</span>
                    </div>
                    <div class="progress-bar-bg">
                        <div class="progress-bar-fill" id="progress-fill"></div>
                    </div>
                    <div class="progress-info">
                        <span id="upload-progress-text">0%</span>
                        <span id="upload-queue-text">File 1 of 1</span>
                    </div>
                </div>
            </div>

            <!-- Clipboard & URL Downloader -->
            <div class="card">
                <div class="card-title"><span>📋</span> Instant Clipboard Sync</div>
                <div class="clipboard-box">
                    <textarea id="clipboard-input" placeholder="Paste text, links, or notes to send to mobile phone..."></textarea>
                    <div style="display:flex; gap:8px;">
                        <button class="btn" onclick="sendClipboard()" style="flex:1;">Send to Phone</button>
                        <button class="btn btn-secondary" onclick="fetchClipboard()">Fetch from Phone</button>
                    </div>
                    <div style="margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--border);">
                        <div style="font-size:0.85rem; font-weight:600; margin-bottom:6px; color:var(--text-muted);">Download from Web URL to Phone:</div>
                        <div style="display:flex; gap:8px;">
                            <input type="url" id="url-input" placeholder="https://example.com/app.apk" style="flex:1; background:#0f172a; border:1px solid var(--border); border-radius:8px; padding:8px 12px; color:white; font-size:0.85rem;">
                            <button class="btn btn-secondary btn-small" onclick="downloadFromUrl()">Download</button>
                        </div>
                    </div>
                </div>
            </div>
        </div>

        <!-- Files Manager -->
        <div style="margin-top: 16px;">
            <div class="search-row">
                <input type="text" id="search-input" class="search-input" placeholder="🔍 Search files on mobile device..." oninput="filterFiles()">
                <div class="filter-tabs">
                    <div class="filter-tab active" onclick="setFilter('all', this)">All (<span id="count-all">0</span>)</div>
                    <div class="filter-tab" onclick="setFilter('video', this)">Videos (<span id="count-video">0</span>)</div>
                    <div class="filter-tab" onclick="setFilter('audio', this)">Audio (<span id="count-audio">0</span>)</div>
                    <div class="filter-tab" onclick="setFilter('apk', this)">APKs (<span id="count-apk">0</span>)</div>
                    <div class="filter-tab" onclick="setFilter('doc', this)">Docs (<span id="count-doc">0</span>)</div>
                </div>
            </div>

            <div class="file-list" id="files-container">
                <!-- Rendered dynamically -->
            </div>
        </div>
    </div>

    <div id="toast"></div>

    <script>
        const initialFiles = [$filesListJson];
        let currentFilter = 'all';

        function showToast(msg) {
            const toast = document.getElementById('toast');
            toast.innerText = msg;
            toast.style.display = 'block';
            setTimeout(() => { toast.style.display = 'none'; }, 3000);
        }

        // Render files list
        function renderFiles() {
            const container = document.getElementById('files-container');
            const search = document.getElementById('search-input').value.toLowerCase();
            
            let vCount = 0, aCount = 0, apkCount = 0, docCount = 0;
            initialFiles.forEach(f => {
                if (f.type === 'video') vCount++;
                else if (f.type === 'audio') aCount++;
                else if (f.type === 'apk') apkCount++;
                else docCount++;
            });
            document.getElementById('count-all').innerText = initialFiles.length;
            document.getElementById('count-video').innerText = vCount;
            document.getElementById('count-audio').innerText = aCount;
            document.getElementById('count-apk').innerText = apkCount;
            document.getElementById('count-doc').innerText = docCount;

            const filtered = initialFiles.filter(f => {
                const matchesSearch = f.name.toLowerCase().includes(search);
                if (!matchesSearch) return false;
                if (currentFilter === 'all') return true;
                if (currentFilter === 'video') return f.type === 'video';
                if (currentFilter === 'audio') return f.type === 'audio';
                if (currentFilter === 'apk') return f.type === 'apk';
                if (currentFilter === 'doc') return f.type === 'file' || f.type === 'image';
                return true;
            });

            if (filtered.length === 0) {
                container.innerHTML = '<div style="text-align:center; padding:40px; color:var(--text-muted);">No files found matching criteria.</div>';
                return;
            }

            container.innerHTML = filtered.map(f => {
                let icon = '📄', badgeClass = 'badge-file';
                if (f.type === 'video') { icon = '🎬'; badgeClass = 'badge-video'; }
                else if (f.type === 'audio') { icon = '🎵'; badgeClass = 'badge-audio'; }
                else if (f.type === 'image') { icon = '🖼️'; badgeClass = 'badge-image'; }
                else if (f.type === 'apk') { icon = '🤖'; badgeClass = 'badge-apk'; }

                const safeName = encodeURIComponent(f.name);
                const isMedia = f.type === 'video' || f.type === 'audio';
                const mediaTag = f.type === 'video' ? 'video' : 'audio';

                return `
                <div class="file-card" id="card-` + safeName + `">
                    <div style="flex: 1; min-width: 0;">
                        <div class="file-meta">
                            <div class="file-badge ` + badgeClass + `">` + icon + `</div>
                            <div class="file-info">
                                <span class="file-name">` + escapeHtml(f.name) + `</span>
                                <div class="file-sub">` + f.size + `</div>
                            </div>
                        </div>
                        ` + (isMedia ? `
                        <div class="media-drawer" id="media-` + safeName + `">
                            <` + mediaTag + ` controls preload="none" style="width:100%;">
                                <source src="/files/` + safeName + `">
                            </` + mediaTag + `>
                        </div>
                        ` : '') + `
                    </div>
                    <div class="file-actions">
                        ` + (isMedia ? `
                        <button class="btn btn-secondary btn-small" onclick="toggleMedia('` + safeName + `')">▶ Stream</button>
                        <button class="btn btn-secondary btn-small" onclick="playOnMobile('` + safeName + `')">📱 Play on Phone</button>
                        ` : '') + `
                        <a href="/files/` + safeName + `" download class="btn btn-small" style="text-decoration:none;">⬇ Download</a>
                        <button class="btn btn-danger btn-small" onclick="deleteFile('` + safeName + `')">🗑</button>
                    </div>
                </div>
                `;
            }).join('');
        }

        function escapeHtml(text) {
            return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        function setFilter(filter, el) {
            currentFilter = filter;
            document.querySelectorAll('.filter-tab').forEach(t => t.classList.remove('active'));
            el.classList.add('active');
            renderFiles();
        }

        function filterFiles() {
            renderFiles();
        }

        function toggleMedia(name) {
            const drawer = document.getElementById('media-' + name);
            if (drawer) {
                drawer.style.display = drawer.style.display === 'block' ? 'none' : 'block';
            }
        }

        function playOnMobile(name) {
            fetch('/play/' + name).then(r => {
                if (r.ok) showToast('Playing on mobile device!');
                else showToast('Failed to trigger play on mobile');
            });
        }

        function deleteFile(name) {
            if (!confirm('Are you sure you want to delete this file from the phone?')) return;
            fetch('/api/delete/' + name, { method: 'POST' }).then(r => {
                if (r.ok) {
                    showToast('File deleted');
                    setTimeout(() => location.reload(), 600);
                } else {
                    showToast('Failed to delete file');
                }
            });
        }

        // Clipboard
        function sendClipboard() {
            const text = document.getElementById('clipboard-input').value;
            if (!text) return;
            fetch('/api/clipboard', { method: 'POST', body: text }).then(r => {
                if (r.ok) showToast('Sent to phone clipboard!');
            });
        }

        function fetchClipboard() {
            fetch('/api/clipboard').then(r => r.text()).then(text => {
                if (text) {
                    document.getElementById('clipboard-input').value = text;
                    navigator.clipboard.writeText(text);
                    showToast('Fetched & copied to PC clipboard!');
                } else {
                    showToast('Phone clipboard is empty');
                }
            });
        }

        function downloadFromUrl() {
            const url = document.getElementById('url-input').value.trim();
            if (!url) return;
            showToast('Starting download to phone...');
            fetch('/download-url', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: 'url=' + encodeURIComponent(url)
            }).then(r => {
                if (r.ok) {
                    showToast('Downloaded to phone!');
                    setTimeout(() => location.reload(), 1200);
                } else {
                    showToast('Failed to download URL');
                }
            });
        }

        // Drag & Drop
        const dropzone = document.getElementById('dropzone');
        ['dragenter', 'dragover'].forEach(name => {
            dropzone.addEventListener(name, (e) => { e.preventDefault(); dropzone.classList.add('dragover'); }, false);
        });
        ['dragleave', 'drop'].forEach(name => {
            dropzone.addEventListener(name, (e) => { e.preventDefault(); dropzone.classList.remove('dragover'); }, false);
        });
        dropzone.addEventListener('drop', (e) => {
            const dt = e.dataTransfer;
            const files = dt.files;
            handleFiles(files);
        });

        // Multi-file chunked upload queue
        let uploadQueue = [];
        let isUploading = false;

        function handleFiles(files) {
            if (!files || files.length === 0) return;
            for (let i = 0; i < files.length; i++) {
                uploadQueue.push(files[i]);
            }
            if (!isUploading) {
                processNextUpload();
            }
        }

        function processNextUpload() {
            if (uploadQueue.length === 0) {
                isUploading = false;
                document.getElementById('upload-status').style.display = 'none';
                showToast('All files transferred successfully!');
                setTimeout(() => location.reload(), 800);
                return;
            }

            isUploading = true;
            const file = uploadQueue.shift();
            const totalInQueue = uploadQueue.length + 1;

            const statusBox = document.getElementById('upload-status');
            const fileNameEl = document.getElementById('upload-file-name');
            const progressFill = document.getElementById('progress-fill');
            const progressText = document.getElementById('upload-progress-text');
            const queueText = document.getElementById('upload-queue-text');
            const speedEl = document.getElementById('upload-speed');

            statusBox.style.display = 'block';
            fileNameEl.innerText = file.name;
            queueText.innerText = 'Remaining: ' + uploadQueue.length;

            const chunkSize = 5 * 1024 * 1024; // 5MB chunks
            const totalChunks = Math.ceil(file.size / chunkSize);
            let currentChunk = 0;
            let lastTime = Date.now();
            let lastLoaded = 0;

            function uploadChunk() {
                const start = currentChunk * chunkSize;
                const end = Math.min(start + chunkSize, file.size);
                const chunk = file.slice(start, end);

                const formData = new FormData();
                formData.append('fileName', file.name);
                formData.append('chunkIndex', currentChunk);
                formData.append('totalChunks', totalChunks);
                formData.append('file', chunk, file.name);

                const xhr = new XMLHttpRequest();
                xhr.open('POST', '/upload/chunk', true);

                xhr.upload.onprogress = (e) => {
                    const loaded = start + e.loaded;
                    const percent = Math.round((loaded / file.size) * 100);
                    progressFill.style.width = percent + '%';
                    progressText.innerText = percent + '% (' + (loaded / (1024*1024)).toFixed(1) + ' / ' + (file.size / (1024*1024)).toFixed(1) + ' MB)';

                    const now = Date.now();
                    const diff = (now - lastTime) / 1000;
                    if (diff > 0.5) {
                        const speed = ((loaded - lastLoaded) / (1024 * 1024)) / diff;
                        speedEl.innerText = speed.toFixed(1) + ' MB/s';
                        lastTime = now;
                        lastLoaded = loaded;
                    }
                };

                xhr.onload = () => {
                    if (xhr.status === 200) {
                        currentChunk++;
                        if (currentChunk < totalChunks) {
                            uploadChunk();
                        } else {
                            progressFill.style.width = '100%';
                            processNextUpload();
                        }
                    } else {
                        showToast('Error uploading chunk of ' + file.name);
                        isUploading = false;
                    }
                };

                xhr.onerror = () => {
                    showToast('Network error during upload');
                    isUploading = false;
                };

                xhr.send(formData);
            }

            uploadChunk();
        }

        renderFiles();
    </script>
</body>
</html>
                                """.trimIndent()
                            }
                        }

                        get("/api/clipboard") {
                            call.respondText(sharedClipboard.value)
                        }

                        post("/api/clipboard") {
                            val text = call.receiveText()
                            sharedClipboard.value = text
                            call.respondText("OK")
                        }

                        get("/api/check-space") {
                            val sizeParam = call.request.queryParameters["size"]?.toLongOrNull() ?: 0L
                            val freeSpace = uploadDir.freeSpace
                            if (sizeParam + (50 * 1024 * 1024) > freeSpace) {
                                call.respondText("Insufficient space", status = HttpStatusCode.InsufficientStorage)
                            } else {
                                call.respondText("OK", status = HttpStatusCode.OK)
                            }
                        }

                        get("/files/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val file = File(uploadDir, filename)
                                if (file.exists()) {
                                    call.respondFile(file)
                                } else {
                                    call.respondText("File not found", status = HttpStatusCode.NotFound)
                                }
                            } else {
                                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                            }
                        }

                        get("/play/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val file = File(uploadDir, filename)
                                if (file.exists()) {
                                    playRequests.tryEmit(file)
                                    call.respondText("OK", status = HttpStatusCode.OK)
                                } else {
                                    call.respondText("File not found", status = HttpStatusCode.NotFound)
                                }
                            } else {
                                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                            }
                        }

                        post("/api/delete/{filename}") {
                            val filename = call.parameters["filename"]
                            if (filename != null) {
                                val file = File(uploadDir, filename)
                                if (file.exists() && file.delete()) {
                                    refreshFiles()
                                    call.respondText("OK", status = HttpStatusCode.OK)
                                } else {
                                    call.respondText("File not found", status = HttpStatusCode.NotFound)
                                }
                            } else {
                                call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
                            }
                        }

                        post("/download-url") {
                            var wakeLock: PowerManager.WakeLock? = null
                            var wifiLock: WifiManager.WifiLock? = null
                            try {
                                val powerManager = this@ServerManager.context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskDrop::UrlWakeLock")
                                wakeLock.acquire(10 * 60 * 1000L)

                                val wifiManager = this@ServerManager.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeskDrop::UrlWifiLock")
                                wifiLock.acquire()

                                val form = call.receiveMultipart()
                                var downloadUrl = ""
                                form.forEachPart { part ->
                                    if (part is PartData.FormItem && part.name == "url") {
                                        downloadUrl = part.value
                                    }
                                    part.dispose()
                                }

                                if (downloadUrl.isBlank()) {
                                    call.respondText("Missing URL", status = HttpStatusCode.BadRequest)
                                    return@post
                                }

                                val url = URL(downloadUrl)
                                val connection = url.openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 15000
                                connection.readTimeout = 30000
                                connection.instanceFollowRedirects = true

                                val responseCode = connection.responseCode
                                if (responseCode !in 200..299) {
                                    call.respondText("Failed to connect to URL: $responseCode", status = HttpStatusCode.BadGateway)
                                    return@post
                                }

                                val rawName = url.path.substringAfterLast('/', "")
                                val fileName = if (rawName.isNotBlank()) rawName else "download_${System.currentTimeMillis()}"
                                val targetFile = File(uploadDir, fileName)

                                connection.inputStream.use { input ->
                                    FileOutputStream(targetFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }

                                refreshFiles()
                                call.respondText("Success", status = HttpStatusCode.OK)
                            } catch (e: Exception) {
                                Log.e("ServerManager", "Download from URL failed", e)
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            } finally {
                                try {
                                    wakeLock?.let { if (it.isHeld) it.release() }
                                    wifiLock?.let { if (it.isHeld) it.release() }
                                } catch (e: Exception) {
                                    Log.e("ServerManager", "Error releasing locks", e)
                                }
                            }
                        }

                        post("/upload/chunk") {
                            var wakeLock: PowerManager.WakeLock? = null
                            var wifiLock: WifiManager.WifiLock? = null
                            try {
                                val powerManager = this@ServerManager.context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeskDrop::UploadWakeLock")
                                wakeLock.acquire(10 * 60 * 1000L)

                                val wifiManager = this@ServerManager.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeskDrop::UploadWifiLock")
                                wifiLock.acquire()

                                val multipart = call.receiveMultipart()
                                var fileName = ""
                                var chunkIndex = -1
                                var totalChunks = -1

                                multipart.forEachPart { part ->
                                    when (part) {
                                        is PartData.FormItem -> {
                                            when (part.name) {
                                                "fileName" -> fileName = part.value
                                                "chunkIndex" -> chunkIndex = part.value.toIntOrNull() ?: -1
                                                "totalChunks" -> totalChunks = part.value.toIntOrNull() ?: -1
                                            }
                                        }
                                        is PartData.FileItem -> {
                                            if (fileName.isNotEmpty() && chunkIndex != -1) {
                                                val tempFile = File(uploadDir, "$fileName.part")
                                                if (chunkIndex == 0 && tempFile.exists()) {
                                                    tempFile.delete()
                                                }
                                                part.streamProvider().use { input ->
                                                    FileOutputStream(tempFile, true).use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                            }
                                        }
                                        else -> {}
                                    }
                                    part.dispose()
                                }

                                if (chunkIndex != -1 && totalChunks != -1 && chunkIndex == totalChunks - 1) {
                                    val tempFile = File(uploadDir, "$fileName.part")
                                    val finalFile = File(uploadDir, fileName)
                                    if (finalFile.exists()) finalFile.delete()
                                    tempFile.renameTo(finalFile)
                                    refreshFiles()
                                }
                                call.respondText("Success", status = HttpStatusCode.OK)
                            } catch (e: Exception) {
                                Log.e("ServerManager", "Upload chunk error", e)
                                call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
                            } finally {
                                try {
                                    wakeLock?.let { if (it.isHeld) it.release() }
                                    wifiLock?.let { if (it.isHeld) it.release() }
                                } catch (e: Exception) {
                                    Log.e("ServerManager", "Error releasing locks", e)
                                }
                            }
                        }
                    }
                }.start(wait = false)
                
                started = true
                this.currentPort = currentPort
                registerService(currentPort)
            } catch (e: Exception) {
                Log.w("ServerManager", "Failed to bind to port $currentPort", e)
                currentPort++
                attempts++
            }
        }
        if (!started) {
            Log.e("ServerManager", "Could not start server after 5 attempts")
        }
    }

    fun stopServer() {
        unregisterService()
        server?.stop(1000, 2000)
        server = null
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var bestAddress: String? = null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        bestAddress = ip
                    }
                }
            }
            return bestAddress
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
