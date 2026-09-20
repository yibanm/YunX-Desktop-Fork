package com.yunx.app.data.backup

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 通用备份：把收藏 / 解析历史 / 下载记录 / 网盘认证 打包为 JSON，
 * 上传到任意 WebDAV 服务器的 YunX/ 目录下（自动创建），或从服务器拉取恢复。
 *
 * 备份文件名格式：yunx_backup_yyyyMMdd_HHmmss.json。
 * 所有方法均为 suspend，应在 IO 调度上调用；失败抛异常由调用方 catch 后提示。
 */
class WebDavBackupManager {

    data class Config(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    data class BackupOptions(
        val includeFavorites: Boolean = true,
        val includeLinkHistory: Boolean = true,
        val includeDownloadRecords: Boolean = true,
        val includeAuth: Boolean = false
    )

    /** 备份文件条目（供还原列表选择） */
    data class BackupFile(
        val name: String,
        val lastModified: Long,
        val size: Long
    )

    /** 连接测试的单步结果（[ok] 为该步是否通过，[detail] 为人类可读说明） */
    data class TestStep(
        val name: String,
        val ok: Boolean,
        val detail: String
    )

    companion object {
        const val APP_TAG = "yunx_backup"
        const val VERSION = 1
        const val APP_DIR = "YunX"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        private const val TAG = "YunX-WebDAV"
        private const val USER_AGENT = "YunX-Desktop-WebDAV/1.1.7"

        /** 服务器预设（一键填充地址，用户名密码仍需自行填写） */
        val PRESETS: LinkedHashMap<String, String> = linkedMapOf(
            "坚果云" to "https://dav.jianguoyun.com/dav/",
            "infini-cloud" to "https://wajima.infini-cloud.net/dav/",
            "Terabox" to "https://dav.terabox.com/dav/",
            "Koofr" to "https://app.koofr.net/dav/Koofr",
            "4shared" to "https://webdav.4shared.com/"
        )
    }

    /** 按选项构建备份 JSON 字符串（不进行网络请求）。 */
    suspend fun buildBackupJson(options: BackupOptions): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.get()
        val root = JSONObject()
            .put("app", APP_TAG)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())

        if (options.includeFavorites) {
            val arr = JSONArray()
            db.bookmarkDao().observeAll().first().forEach { bm ->
                arr.put(
                    JSONObject()
                        .put("link", bm.link)
                        .put("title", bm.title)
                        .put("platform", bm.platform)
                        .put("pwd", bm.pwd)
                        .put("category", bm.category)
                        .put("createTime", bm.createTime)
                )
            }
            root.put("favorites", arr)
        }

        if (options.includeLinkHistory) {
            val arr = JSONArray()
            db.linkHistoryDao().observeAll().first().forEach { h ->
                arr.put(
                    JSONObject()
                        .put("url", h.url)
                        .put("title", h.title)
                        .put("platform", h.platform)
                        .put("pwd", h.pwd)
                        .put("createTime", h.createTime)
                )
            }
            root.put("linkHistory", arr)
        }

        if (options.includeDownloadRecords) {
            val arr = JSONArray()
            db.downloadTaskDao().observeAll().first().forEach { t ->
                arr.put(
                    JSONObject()
                        .put("url", t.url)
                        .put("fileName", t.fileName)
                        .put("totalSize", t.totalSize)
                        .put("status", t.status)
                        .put("platform", t.platform)
                        .put("savePath", t.savePath)
                        .put("createTime", t.createTime)
                )
            }
            root.put("downloadRecords", arr)
        }

        if (options.includeAuth) {
            root.put("auth", buildAuthManager().exportJson(onlyLoggedIn = true))
        }

        root.toString(2)
    }

    /** 构建（恢复用）认证管理器，复用与网盘认证页相同的 DAO 装配。 */
    private fun buildAuthManager(): AuthBackupManager {
        val db = AppDatabase.get()
        return AuthBackupManager(
            quarkDao = db.quarkAccountDao(),
            ucDao = db.ucAccountDao(),
            xunleiDao = db.xunleiAccountDao(),
            baiduDao = db.baiduAccountDao(),
            c139Dao = db.c139AccountDao(),
            pan123Dao = db.pan123AccountDao()
        )
    }

    /** 生成带时间戳的备份文件名：yunx_backup_yyyyMMdd_HHmmss.json */
    private fun timestampedName(now: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "yunx_backup_${sdf.format(Date(now))}.json"
    }

    /**
     * 备份到 WebDAV：确保 YunX/ 目录存在，上传带时间戳的备份文件。
     * @return 上传后的文件名
     */
    suspend fun backup(config: Config, options: BackupOptions): String = withContext(Dispatchers.IO) {
        ensureAppDir(config)
        val json = buildBackupJson(options)
        val fileName = timestampedName()
        put(config, "$APP_DIR/$fileName", json.toByteArray(StandardCharsets.UTF_8))
        fileName
    }

    /** 兼容旧调用名：备份到 WebDAV（固定文件名 yunx-backup.json，根目录） */
    suspend fun backupToWebDav(config: Config, options: BackupOptions): Unit =
        withContext(Dispatchers.IO) {
            val json = buildBackupJson(options)
            put(config, "yunx-backup.json", json.toByteArray(StandardCharsets.UTF_8))
        }

    /**
     * 连接自检：依次验证 连通认证 → 建目录 → 上传 → 下载 → 删除清理。
     * 不抛异常，返回每一步的通过情况，便于区分"能浏览但不能上传（流量/权限）"等问题。
     */
    suspend fun testConnection(config: Config): List<TestStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<TestStep>()
        fun reason(t: Throwable): String = t.message ?: t.javaClass.simpleName

        // 1. 连通与认证（PROPFIND 根目录）
        val authResult = runCatching { propfind(config, "", depth = "0") }
        if (authResult.isFailure) {
            steps += TestStep("连接与认证", false, authResult.exceptionOrNull()?.let(::reason)
                ?: "无法连接服务器，请检查地址、网络")
            return@withContext steps
        }
        steps += TestStep("连接与认证", true, "服务器可达，账号密码有效")

        // 2. 创建/访问备份目录
        val dirOk = runCatching { ensureAppDir(config) }.isSuccess
        if (!dirOk) {
            val msg = runCatching { ensureAppDir(config) }.exceptionOrNull()?.let(::reason)
                ?: "无法创建备份目录"
            steps += TestStep("创建备份目录 YunX", false, msg)
            return@withContext steps
        }
        steps += TestStep("创建备份目录 YunX", true, "目录已存在或创建成功")

        // 3. 上传（写入权限）——坚果云免费版流量用尽时通常就卡在这一步
        val probeName = "yunx_probe_${System.currentTimeMillis()}.txt"
        val probePath = "$APP_DIR/$probeName"
        val uploadErr = runCatching {
            put(config, probePath, "yunx probe".toByteArray(StandardCharsets.UTF_8))
        }.exceptionOrNull()
        if (uploadErr != null) {
            steps += TestStep("上传（写入权限）", false, reason(uploadErr))
            return@withContext steps
        }
        steps += TestStep("上传（写入权限）", true, "测试文件上传成功")

        // 4. 下载（读取权限）并校验内容
        val readErr = runCatching {
            val b = get(config, probePath)
            check(String(b, StandardCharsets.UTF_8).trim().contains("yunx probe"))
        }.exceptionOrNull()
        if (readErr != null) {
            steps += TestStep("下载（读取权限）", false, reason(readErr))
        } else {
            steps += TestStep("下载（读取权限）", true, "测试文件读回一致")
        }

        // 5. 删除清理（best-effort，不影响结论）
        runCatching { delete(config, probePath) }
        steps
    }

    /**
     * 列出 YunX/ 目录下所有备份文件（.json），按最后修改时间降序（最新在最上面）。
     */
    suspend fun listBackups(config: Config): List<BackupFile> = withContext(Dispatchers.IO) {
        val xml = propfind(config, APP_DIR)
        parsePropfind(xml)
            .filter { it.name.endsWith(".json", ignoreCase = true) }
            .sortedByDescending { it.lastModified }
    }

    /**
     * 从 WebDAV 还原指定备份文件。
     * @return 还原的记录条数（收藏+历史+下载记录粗略计数）
     */
    suspend fun restore(config: Config, fileName: String): Int = withContext(Dispatchers.IO) {
        val bytes = get(config, "$APP_DIR/$fileName")
        val text = String(bytes, StandardCharsets.UTF_8)
        restoreFromJson(text)
    }

    /** 兼容旧调用名：从默认固定备份还原 */
    suspend fun restoreFromWebDav(config: Config): Unit = withContext(Dispatchers.IO) {
        val bytes = get(config, "yunx-backup.json")
        val text = String(bytes, StandardCharsets.UTF_8)
        restoreFromJson(text)
    }

    /** 从备份 JSON 字符串恢复（收藏 / 历史 / 下载记录 / 认证），返回还原记录条数。 */
    suspend fun restoreFromJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("不是有效的云析备份文件")
        }
        val db = AppDatabase.get()
        var restored = 0

        root.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    db.bookmarkDao().insert(
                        com.yunx.app.data.db.BookmarkEntity(
                            link = o.optString("link"),
                            title = o.optString("title"),
                            platform = o.optString("platform"),
                            pwd = o.optString("pwd"),
                            category = o.optString("category", com.yunx.app.data.db.BookmarkEntity.DEFAULT_CATEGORY),
                            createTime = o.optLong("createTime", System.currentTimeMillis())
                        )
                    )
                    restored++
                }
            }
        }

        root.optJSONArray("linkHistory")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    db.linkHistoryDao().insert(
                        com.yunx.app.data.db.LinkHistoryEntity(
                            url = o.optString("url"),
                            title = o.optString("title"),
                            platform = o.optString("platform"),
                            pwd = o.optString("pwd"),
                            createTime = o.optLong("createTime", System.currentTimeMillis())
                        )
                    )
                    restored++
                }
            }
        }

        root.optString("auth", "").takeIf { it.isNotBlank() }?.let { authJson ->
            runCatching { buildAuthManager().importJson(authJson) }
        }

        restored
    }

    // ---------- 极简 WebDAV HTTP 客户端（OkHttp，Basic 认证） ----------
    // 注意：JDK 的 HttpURLConnection 只允许 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
    // 对 MKCOL/PROPFIND 会直接抛 ProtocolException("Invalid HTTP method")，因此必须走 OkHttp。

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val xmlMedia = "application/xml; charset=utf-8".toMediaType()
    private val propfindBody =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>")
            .toByteArray(StandardCharsets.UTF_8)
            .toRequestBody(xmlMedia)

    // WebDAV 是简单请求/响应协议，强制 HTTP/1.1：部分服务器（坚果云网关、部分 mod_dav）
    // 对 HTTP/2 上的 PUT/MKCOL 兼容不佳，会返回 403；同时显式 User-Agent，避免被 WAF 拦截。
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun buildUrl(config: Config, path: String): String {
        val base = config.serverUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$base$p"
    }

    private fun authHeader(config: Config): String {
        val raw = "${config.username}:${config.password}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    /** 统一执行：加 Basic 认证与 User-Agent，记录日志，返回 HTTP 状态码与响应字节（错误响应同样读取）。 */
    private fun execute(config: Config, method: String, path: String, builder: Request.Builder): Pair<Int, ByteArray> {
        val url = buildUrl(config, path)
        val req = builder
            .url(url)
            .header("Authorization", authHeader(config))
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            // 401 体里可能带 WWW-Authenticate，不打印密码
            Log.d(TAG, "$method ${config.serverUrl.trimEnd('/')}/$path -> ${resp.code} (${bytes.size}B)")
            return resp.code to bytes
        }
    }

    /** 从错误响应体里提取一段纯文本，便于把服务器拒绝原因直接展示给用户。 */
    private fun serverReason(bytes: ByteArray): String {
        val plain = String(bytes, StandardCharsets.UTF_8)
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (plain.isBlank()) "" else "；服务器返回：${plain.take(160)}"
    }

    /** 常见状态码的可操作提示。 */
    private fun statusHint(code: Int): String = when (code) {
        401 -> "账号或密码错误（坚果云等需使用「第三方应用密码」，不是登录密码）"
        403 -> "无写入权限：能浏览/建目录却不能上传，最常见是坚果云免费版每月 1GB 上传流量已用尽（次月恢复或升级），也可能是应用密码无写入权限"
        409 -> "上级目录不存在或存在同名文件"
        507 -> "服务器存储空间不足"
        else -> ""
    }

    private fun ioError(action: String, code: Int, bytes: ByteArray): java.io.IOException {
        val hint = statusHint(code)
        val tail = buildString {
            if (hint.isNotBlank()) append("（$hint）")
            append(serverReason(bytes))
        }
        Log.w(TAG, "$action 失败 HTTP $code $tail")
        return java.io.IOException("WebDAV ${action}失败：HTTP $code$tail")
    }

    /** 目录是否存在（PROPFIND Depth:0），任何非 2xx 都视为不存在/不可访问。 */
    private fun dirExists(config: Config, path: String): Boolean {
        val (code, _) = runCatching {
            execute(
                config, "PROPFIND", path,
                Request.Builder().method("PROPFIND", propfindBody).header("Depth", "0")
            )
        }.getOrNull() ?: return false
        return code in 200..299
    }

    /**
     * 确保 YunX/ 目录存在：
     * PROPFIND 探测 -> 不存在则逐级 MKCOL -> 再 PROPFIND 复核，避免把"无权建目录"误判成成功。
     */
    private fun ensureAppDir(config: Config) {
        if (dirExists(config, APP_DIR)) return
        mkcolWithParents(config, APP_DIR)
        if (!dirExists(config, APP_DIR)) {
            throw java.io.IOException("WebDAV 备份目录 $APP_DIR 创建后仍无法访问，请检查该账号是否有写入权限")
        }
    }

    /** 逐级创建目录（409 先建父目录）；201/405(已存在) 均可接受，其余状态码抛错。 */
    private fun mkcolWithParents(config: Config, path: String) {
        val (code, bytes) = execute(config, "MKCOL", path, Request.Builder().method("MKCOL", null))
        if (code in 200..299 || code == 405) return
        if (code == 409) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotBlank() && parent != path) {
                mkcolWithParents(config, parent)
                val (code2, bytes2) = execute(config, "MKCOL", path, Request.Builder().method("MKCOL", null))
                if (code2 in 200..299 || code2 == 405) return
                throw ioError("创建目录", code2, bytes2)
            }
        }
        throw ioError("创建目录", code, bytes)
    }

    /** PROPFIND 列目录，返回响应 XML 字符串 */
    private fun propfind(config: Config, path: String, depth: String = "1"): String {
        val (code, bytes) = execute(
            config, "PROPFIND", path,
            Request.Builder().method("PROPFIND", propfindBody).header("Depth", depth)
        )
        if (code !in 200..299) throw ioError("列目录", code, bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** 用正则解析 PROPFIND multistatus XML，提取 href / getlastmodified / getcontentlength */
    internal fun parsePropfind(xml: String): List<BackupFile> {
        val result = mutableListOf<BackupFile>()
        // 每个 <D:response>...</D:response> 块
        val responseRegex = Regex("<[^:>]*:?response[^>]*>([\\s\\S]*?)</[^:>]*:?response>")
        for (m in responseRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val href = Regex("<[^:>]*:?href[^>]*>([^<]+)</[^:>]*:?href>")
                .find(block)?.groupValues?.get(1)?.trim() ?: continue
            // 跳过当前目录自身（href 以目录名结尾且无文件名）
            val decoded = URLDecoder.decode(href, "UTF-8")
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue
            val lastMod = Regex("<[^:>]*:?getlastmodified[^>]*>([^<]+)</[^:>]*:?getlastmodified>")
                .find(block)?.groupValues?.get(1)?.trim()
            val sizeStr = Regex("<[^:>]*:?getcontentlength[^>]*>([^<]+)</[^:>]*:?getcontentlength>")
                .find(block)?.groupValues?.get(1)?.trim()
            val size = sizeStr?.toLongOrNull() ?: 0L
            val modTs = parseHttpDate(lastMod)
            result.add(BackupFile(name = name, lastModified = modTs, size = size))
        }
        return result
    }

    /** 解析 HTTP 日期（RFC 1123，如 "Wed, 16 Sep 2026 10:20:30 GMT"） */
    private fun parseHttpDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            sdf.parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun put(config: Config, path: String, body: ByteArray) {
        val (code, bytes) = execute(
            config, "PUT", path,
            Request.Builder().put(body.toRequestBody(jsonMedia))
        )
        if (code !in 200..299) throw ioError("上传", code, bytes)
    }

    private fun get(config: Config, path: String): ByteArray {
        val (code, bytes) = execute(config, "GET", path, Request.Builder().get())
        if (code !in 200..299) throw ioError("下载", code, bytes)
        return bytes
    }

    private fun delete(config: Config, path: String) {
        val (code, bytes) = execute(config, "DELETE", path, Request.Builder().delete())
        if (code !in 200..299 && code != 404) throw ioError("删除", code, bytes)
    }

    /**
     * 本地备份目录：默认「文档/YunX-Desktop」，可在设置页自定义（[com.yunx.app.AppContext.backupDir]）。
     * WebDAV 远端仍固定放在服务器的 YunX/ 目录下。
     */
    internal fun localDir(): File = AppContext.backupDir.apply { mkdirs() }

    /** 当前本地备份目录的绝对路径（设置页展示与"打开文件夹"用） */
    fun localDirPath(): String = localDir().absolutePath

    // ---------- 本地备份（与 WebDAV 对齐，默认存放在 文档/YunX-Desktop/） ----------

    /** 备份到本地：在 dataDir/YunX/ 下保存带时间戳的 json 文件，返回文件 */
    suspend fun backupLocal(options: BackupOptions): File = withContext(Dispatchers.IO) {
        val dir = localDir()
        val json = buildBackupJson(options)
        val name = timestampedName()
        val f = File(dir, name)
        f.writeText(json, StandardCharsets.UTF_8)
        f
    }

    /** 列出本地 YunX/ 目录下所有 .json 备份，按最后修改时间降序（最新在最上面） */
    suspend fun listLocalBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val dir = localDir()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            ?.map { BackupFile(name = it.name, lastModified = it.lastModified(), size = it.length()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /** 还原指定本地备份文件 */
    suspend fun restoreLocal(fileName: String): Int = withContext(Dispatchers.IO) {
        val f = File(localDir(), fileName)
        if (!f.exists()) throw java.io.FileNotFoundException("本地备份不存在：$fileName")
        restoreFromJson(f.readText(StandardCharsets.UTF_8))
    }
}
