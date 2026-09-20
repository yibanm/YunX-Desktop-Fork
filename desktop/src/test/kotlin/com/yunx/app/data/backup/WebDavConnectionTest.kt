package com.yunx.app.data.backup

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDAV 连接自检（testConnection）回归测试。
 *
 * 重点覆盖用户实际遇到的"能浏览/建目录，但一上传就 403"场景（坚果云免费版上传流量
 * 用尽时正是这个表现）：自检必须把失败定位到"上传"这一步并带上可操作提示，而不是
 * 笼统报一个 403，也不能抛异常中断。
 */
class WebDavConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: WebDavBackupManager
    private lateinit var config: WebDavBackupManager.Config

    /** true=上传放行；false=PUT 一律 403（模拟流量/权限被拒） */
    private var allowUpload = true

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/YunX/</D:href></D:response></D:multistatus>""".trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val method = request.method
                return when {
                    method == "PROPFIND" && path.trimEnd('/').endsWith("/dav") ->
                        MockResponse().setResponseCode(207).setBody(multistatus)
                    method == "PROPFIND" && path.contains("/YunX") ->
                        MockResponse().setResponseCode(207).setBody(multistatus)
                    method == "MKCOL" -> MockResponse().setResponseCode(201)
                    method == "PUT" ->
                        if (allowUpload) MockResponse().setResponseCode(201)
                        else MockResponse().setResponseCode(403).setBody("<error>storageQuotaExceeded</error>")
                    method == "GET" ->
                        MockResponse().setResponseCode(200).setBody("yunx probe")
                    method == "DELETE" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        manager = WebDavBackupManager()
        config = WebDavBackupManager.Config(server.url("/dav/").toString(), "user", "pass")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 一切正常时四步全部通过（认证、建目录、上传、下载）。 */
    @Test
    fun testConnectionAllStepsPass() = runBlocking {
        allowUpload = true
        val steps = manager.testConnection(config)
        steps.forEach { assertTrue("${it.name} 应通过: ${it.detail}", it.ok) }
        assertEquals(4, steps.size)
        assertEquals(listOf("连接与认证", "创建备份目录 YunX", "上传（写入权限）", "下载（读取权限）"),
            steps.map { it.name })
    }

    /** 能浏览/建目录但上传 403：失败必须精确定位到"上传"，并带流量/权限提示，且不抛异常。 */
    @Test
    fun testConnectionPinpointsUploadForbidden() = runBlocking {
        allowUpload = false
        val steps = manager.testConnection(config)

        assertTrue(steps.first { it.name == "连接与认证" }.ok)
        assertTrue(steps.first { it.name == "创建备份目录 YunX" }.ok)
        val upload = steps.first { it.name == "上传（写入权限）" }
        assertFalse(upload.ok)
        assertTrue(upload.detail.contains("HTTP 403"))
        assertTrue("应提示流量/应用密码，实际：${upload.detail}",
            upload.detail.contains("流量") || upload.detail.contains("应用密码"))
        assertTrue(upload.detail.contains("storageQuotaExceeded"))
        // 上传失败后不应再出现下载步骤
        assertFalse(steps.any { it.name == "下载（读取权限）" })
    }

    /** 根地址就 401 时，只返回认证失败一步并提示，不继续后续请求。 */
    @Test
    fun testConnectionStopsAtAuthFailure() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val steps = manager.testConnection(config)
        assertEquals(1, steps.size)
        assertFalse(steps[0].ok)
        assertTrue(steps[0].detail.contains("HTTP 401"))
    }
}
