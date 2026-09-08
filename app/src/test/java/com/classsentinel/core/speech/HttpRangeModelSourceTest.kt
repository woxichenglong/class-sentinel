package com.classsentinel.core.speech

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpRangeModelSourceTest {

    private lateinit var server: MockWebServer
    private val modelFiles = linkedMapOf(
        "encoder.onnx" to "abcdefghij".toByteArray(),
        "decoder.onnx" to "klmnop".toByteArray(),
        "joiner.onnx" to "qrstuvwx".toByteArray(),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `no part and 200 downloads the complete artifact`() {
        val requests = installWithDispatcher { request -> fullResponse(fileFor(request)) }
        val root = Files.createTempDirectory("range-no-part-").toFile()
        try {
            val profile = testProfile()
            val target = RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()

            assertComplete(profile, root, target)
            assertTrue(requests.all { it.getHeader("Range") == null })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing part and 206 append from the requested offset`() {
        val requests = installWithDispatcher { request ->
            val name = fileName(request)
            val full = modelFiles.getValue(name)
            val range = request.getHeader("Range")
            if (name == "encoder.onnx" && range == "bytes=3-") {
                partialResponse(full, 3)
            } else {
                fullResponse(full)
            }
        }
        val root = Files.createTempDirectory("range-append-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            File(target, "encoder.onnx.part").writeBytes(modelFiles.getValue("encoder.onnx").copyOfRange(0, 3))

            RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()

            assertComplete(profile, root, target)
            assertEquals("bytes=3-", requests.first { fileName(it) == "encoder.onnx" }.getHeader("Range"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `server ignoring range with 200 replaces instead of appending`() {
        val requests = installWithDispatcher { request -> fullResponse(fileFor(request)) }
        val root = Files.createTempDirectory("range-ignore-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            val encoder = modelFiles.getValue("encoder.onnx")
            File(target, "encoder.onnx.part").writeBytes(encoder.copyOfRange(0, 3))

            RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()

            assertArrayEquals(encoder, File(target, "encoder.onnx").readBytes())
            assertEquals(encoder.size.toLong(), File(target, "encoder.onnx").length())
            assertEquals("bytes=3-", requests.first { fileName(it) == "encoder.onnx" }.getHeader("Range"))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `206 with a wrong content range start fails before promotion`() {
        installWithDispatcher { request ->
            val full = fileFor(request)
            if (fileName(request) == "encoder.onnx" && request.getHeader("Range") == "bytes=3-") {
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-${full.lastIndex}/${full.size}")
                    .setBody(Buffer().write(full.copyOfRange(3, full.size)))
            } else {
                fullResponse(full)
            }
        }
        val root = Files.createTempDirectory("range-invalid-content-range-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            File(target, "encoder.onnx.part").writeBytes(modelFiles.getValue("encoder.onnx").copyOfRange(0, 3))

            assertThrows(IllegalStateException::class.java) {
                RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()
            }

            assertFalse(File(target, "encoder.onnx").exists())
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
            assertFalse(File(target, ".model-profile").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `part longer than expected size resets without sending an oversized range`() {
        val requests = installWithDispatcher { request -> fullResponse(fileFor(request)) }
        val root = Files.createTempDirectory("range-oversized-part-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            val encoder = modelFiles.getValue("encoder.onnx")
            File(target, "encoder.onnx.part").writeBytes(encoder + byteArrayOf(99, 100))

            RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()

            assertArrayEquals(encoder, File(target, "encoder.onnx").readBytes())
            assertEquals(null, requests.first { fileName(it) == "encoder.onnx" }.getHeader("Range"))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `full sized corrupt part resets instead of requesting range at expected size`() {
        val requests = installWithDispatcher { request -> fullResponse(fileFor(request)) }
        val root = Files.createTempDirectory("range-corrupt-full-part-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            val encoder = modelFiles.getValue("encoder.onnx")
            File(target, "encoder.onnx.part").writeBytes(ByteArray(encoder.size) { 99 })

            RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient())).install()

            assertArrayEquals(encoder, File(target, "encoder.onnx").readBytes())
            assertEquals(null, requests.first { fileName(it) == "encoder.onnx" }.getHeader("Range"))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `midstream IOException keeps part and a later run resumes it`() {
        var shouldDisconnect = true
        val requests = installWithDispatcher { request ->
            val name = fileName(request)
            val full = modelFiles.getValue(name)
            val range = request.getHeader("Range")
            if (name == "encoder.onnx" && shouldDisconnect) {
                shouldDisconnect = false
                val offset = rangeOffset(range)
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $offset-${full.lastIndex}/${full.size}")
                    .setBody(Buffer().write(full.copyOfRange(offset, full.size)))
                    .also { it.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY }
            } else if (name == "encoder.onnx" && range != null) {
                val offset = rangeOffset(range)
                partialResponse(full, offset)
            } else {
                fullResponse(full)
            }
        }
        val root = Files.createTempDirectory("range-io-resume-").toFile()
        try {
            val profile = testProfile()
            val target = targetDirectory(root, profile)
            val prefix = modelFiles.getValue("encoder.onnx").copyOfRange(0, 2)
            File(target, "encoder.onnx.part").writeBytes(prefix)
            val installer = RemoteModelInstaller(root, profile, HttpRangeModelSource(OkHttpClient()))

            assertThrows(IllegalStateException::class.java) { installer.install() }
            assertTrue(File(target, "encoder.onnx.part").isFile)
            val preservedPart = File(target, "encoder.onnx.part").readBytes()
            assertTrue(preservedPart.size >= prefix.size)
            assertTrue(preservedPart.copyOfRange(0, prefix.size).contentEquals(prefix))

            installer.install()

            assertComplete(profile, root, target)
            assertTrue(requests.count { fileName(it) == "encoder.onnx" } >= 2)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installWithDispatcher(
        handler: (RecordedRequest) -> MockResponse,
    ): MutableList<RecordedRequest> {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        server.start()
        return requests
    }

    private fun testProfile(): ModelProfile {
        val base = ModelProfiles.X_ASR_480
        return base.copy(
            artifact = base.artifact.copy(
                directory = "range-model",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
            distribution = ModelDistribution.Remote(
                files = modelFiles.keys.associateWith { name -> server.url("/$name").toString() },
            ),
        )
    }

    private fun spec(name: String): ModelFileSpec {
        val bytes = modelFiles.getValue(name)
        return ModelFileSpec(name, bytes.size.toLong(), sha256(bytes))
    }

    private fun targetDirectory(root: File, profile: ModelProfile): File =
        File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }

    private fun assertComplete(profile: ModelProfile, root: File, target: File) {
        modelFiles.forEach { (name, bytes) -> assertArrayEquals(bytes, File(target, name).readBytes()) }
        assertTrue(File(target, ".model-profile").isFile)
        assertTrue(ModelIntegrityVerifier.verify(profile, target))
        assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
    }

    private fun fileFor(request: RecordedRequest): ByteArray = modelFiles.getValue(fileName(request))

    private fun fileName(request: RecordedRequest): String = request.path!!.removePrefix("/")

    private fun rangeOffset(range: String?): Int =
        range!!.removePrefix("bytes=").removeSuffix("-").toInt()

    private fun fullResponse(body: ByteArray): MockResponse =
        MockResponse().setResponseCode(200).setBody(Buffer().write(body))

    private fun partialResponse(full: ByteArray, offset: Int): MockResponse =
        MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $offset-${full.lastIndex}/${full.size}")
            .setBody(Buffer().write(full.copyOfRange(offset, full.size)))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
