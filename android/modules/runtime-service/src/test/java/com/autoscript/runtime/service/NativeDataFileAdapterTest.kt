package com.autoscript.runtime.service

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeDataFileAdapterTest {
    @Test fun metadataAndBase64OperationsPreserveBinaryAndRejectOverwrite() {
        val root = File(temporary.root.canonicalFile, "binary-tools")
        val store = ProjectDataStore(root, TestFileOps())
        val commit: (() -> Unit) -> Unit = { it() }
        val commands = ProjectDataCommands(store, {}, commit)
        val bytes = byteArrayOf(0,-1,-128,1,2)
        val encoded = StrictBase64.encode(bytes)
        assertEquals("1", commands.execute(14144,"folder/blob.bin", "[\"$encoded\"]".toByteArray()).toString(Charsets.UTF_8))
        assertEquals(5L, store.fileSize("folder/blob.bin", {}))
        assertEquals("\"bin\"", commands.execute(14141,"folder/blob.bin", "[]".toByteArray()).toString(Charsets.UTF_8))
        assertEquals(encoded, store.toBase64("folder/blob.bin", {}))
        assertEquals("{\"files\":1,\"directories\":0,\"bytes\":5}", store.directoryStats("folder", {}))
        assertThrowsCode("FILE_EXISTS") { store.fromBase64("folder/blob.bin", encoded, {}, commit) }
        assertThrowsCode("FILE_UTF8_INVALID") { store.read("folder/blob.bin", {}) }
    }

    @Test fun largeRecognizedBinaryCanBeRemovedAndRecoveredWithoutConsumingTextQuota() {
        val root=File(temporary.root.canonicalFile,"binary-recycle")
        val store=ProjectDataStore(root,TestFileOps())
        val content=ByteArray(100_000)
        byteArrayOf(80,75,3,4).copyInto(content)
        File(root,"data/bundle.zip").writeBytes(content)
        val commit: (() -> Unit) -> Unit={it()}
        store.remove("bundle.zip",{},commit)
        assertFalse(File(root,"data/bundle.zip").exists())
        val entry=File(root,".trash").listFiles()!!.single()
        store.restore(entry.name,{},commit)
        assertArrayEquals(content,File(root,"data/bundle.zip").readBytes())
    }
    @get:Rule val temporary = TemporaryFolder()

    private open class TestFileOps : ProjectDataFileOps {
        override fun kind(file: File): ProjectDataKind? = try {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when { attributes.isSymbolicLink -> ProjectDataKind.UNSAFE; attributes.isDirectory -> ProjectDataKind.DIRECTORY
                attributes.isRegularFile -> ProjectDataKind.FILE; else -> ProjectDataKind.UNSAFE }
        } catch (_: NoSuchFileException) { null }
        override fun input(file: File): InputStream = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        override fun outputExclusive(file: File): OutputStream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        override fun moveAtomic(source: File, target: File) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    }

    @Test fun exactUtf8ReadWriteAndListAreProjectIsolatedAndCapabilityGated() {
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("project-a", true))
        success(adapter.dispatch(1, 14101, "state/config.json", "汉字\n😀".toByteArray()))
        assertEquals("汉字\n😀", success(adapter.dispatch(2, 14100, "state/config.json", byteArrayOf())))
        assertEquals("[\"state/\"]", success(adapter.dispatch(3, 14102, "", byteArrayOf())))
        assertEquals("[\"state/config.json\"]", success(adapter.dispatch(4, 14102, "state", byteArrayOf())))
        assertTrue(adapter.configure("project-b", true))
        failure("FILE_PATH_INVALID", adapter.dispatch(5, 14100, "state/config.json", byteArrayOf()))
        success(adapter.dispatch(6, 14101, "state/config.json", "other".toByteArray()))
        assertTrue(adapter.configure("project-a", true))
        assertEquals("汉字\n😀", success(adapter.dispatch(7, 14100, "state/config.json", byteArrayOf())))
        assertTrue(adapter.configure("project-a", false))
        failure("FILE_ADAPTER_UNAVAILABLE", adapter.dispatch(8, 14100, "state/config.json", byteArrayOf()))
        assertFalse(adapter.configure("../project-a", true))
        assertFalse(adapter.configure("a".repeat(65), true))
        adapter.close()
        assertFalse(adapter.configure("project-a", true))
    }

    @Test fun pngWritesArePrivateExclusiveAndKeepTheOriginalBytes() {
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("png-project", true))
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteArray(80_000) { (it % 256).toByte() }
        success(adapter.dispatch(1, 14130, "shots/a.png", png))
        val saved = File(temporary.root, "script-data/png-project/data/shots/a.png")
        assertArrayEquals(png, saved.readBytes())
        failure("FILE_EXISTS", adapter.dispatch(2, 14130, "shots/a.png", png))
        failure("FILE_PATH_INVALID", adapter.dispatch(3, 14130, "../outside.png", png))
        failure("FILE_PNG_INVALID", adapter.dispatch(4, 14130, "shots/b.txt", png))
        failure("FILE_PNG_INVALID", adapter.dispatch(5, 14130, "shots/b.png", ByteArray(100)))
        adapter.cancel(6)
        failure("FILE_CANCELLED", adapter.dispatch(6, 14130, "shots/c.png", png))
        assertFalse(File(saved.parentFile, "c.png").exists())
        assertTrue(adapter.configure("other-project", true))
        assertFalse(File(temporary.root, "script-data/other-project/data/shots/a.png").exists())
    }

    @Test fun pngBudgetDoesNotExpandTextQuotaAndExtensionsCannotBypassIt() {
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("mixed-project", true))
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + ByteArray(600_000)
        success(adapter.dispatch(1, 14130, "shot.png", png))
        val text = ByteArray(65_536) { 'x'.code.toByte() }
        for (index in 1..8) success(adapter.dispatch((index + 1).toLong(), 14101, "text$index.png", text))
        failure("FILE_QUOTA", adapter.dispatch(10, 14101, "extra.png", byteArrayOf(1)))
        success(adapter.dispatch(11, 14130, "another.png", png))
        adapter.close()
    }

    @Test fun sourceSecretsAbsoluteTraversalAndReservedPrivatePathsCannotBeRead() {
        File(temporary.root, "main.lua").writeText("private script")
        File(temporary.root, "secret").writeText("private key")
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("project-a", true))
        for (path in listOf("../main.lua", "../../secret", "/main.lua", "C:/secret", "x\\secret", ".trash/file", "x//file", "x/./file", "x/../file", "x/secret.", "x/secret ", "x\u0000secret")) {
            failure("FILE_PATH_INVALID", adapter.dispatch(1, 14100, path, byteArrayOf()))
        }
        failure("FILE_NOT_FOUND", adapter.dispatch(2, 14100, "main.lua", byteArrayOf()))
        failure("FILE_REQUEST_INVALID", adapter.dispatch(3, 14100, "secret", byteArrayOf(1)))
        assertEquals("private key", File(temporary.root, "secret").readText())
    }

    @Test fun boundedUtf8AndTotalQuotaRejectOversizeWithoutReplacingExistingData() {
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("project-a", true))
        val block = ByteArray(65_536) { 'x'.code.toByte() }
        for (index in 1..8) success(adapter.dispatch(index.toLong(), 14101, "file$index", block))
        failure("FILE_QUOTA", adapter.dispatch(9, 14101, "file9", byteArrayOf(1)))
        failure("FILE_REQUEST_INVALID", adapter.dispatch(10, 14101, "file1", ByteArray(65_537)))
        failure("FILE_UTF8_INVALID", adapter.dispatch(11, 14101, "file1", byteArrayOf(-1)))
        assertEquals(block.toString(Charsets.UTF_8), success(adapter.dispatch(12, 14100, "file1", byteArrayOf())))
        val data = File(temporary.root, "script-data/project-a/data")
        File(data, "file1").writeBytes(byteArrayOf(-1))
        failure("FILE_UTF8_INVALID", adapter.dispatch(13, 14100, "file1", byteArrayOf()))
        File(data, "file1").writeBytes(ByteArray(65_537))
        failure("FILE_TOO_LARGE", adapter.dispatch(14, 14100, "file1", byteArrayOf()))
    }

    @Test fun removeIsRecoverableAndCannotDeleteDirectoriesOrEvictTrash() {
        val ops = TestFileOps()
        val store = ProjectDataStore(File(temporary.root.canonicalFile, "project-a"), ops)
        val commit: (() -> Unit) -> Unit = { it() }
        store.write("saved/config.json", "kept".toByteArray(), {}, commit)
        store.remove("saved/config.json", {}, commit)
        assertFalse(File(temporary.root, "project-a/data/saved/config.json").exists())
        val entry = File(temporary.root, "project-a/.trash").listFiles()!!.single()
        assertEquals("saved/config.json", File(entry, "path").readText())
        assertEquals("kept", File(entry, "file").readText())
        assertEquals("saved/config.json", store.restore(entry.name, {}, commit))
        assertEquals("kept", store.read("saved/config.json", {}).toString(Charsets.UTF_8))
        assertFalse(entry.exists())
        assertThrowsCode("FILE_NOT_FOUND") { store.remove("saved", {}, commit) }
        val block = ByteArray(65_536) { 120 }
        for (index in 1..7) { store.write("big$index", block, {}, commit); store.remove("big$index", {}, commit) }
        store.write("big8", block, {}, commit)
        assertThrowsCode("FILE_QUOTA") { store.remove("big8", {}, commit) }
        assertEquals(7, File(temporary.root, "project-a/.trash").listFiles()!!.size)
        assertTrue(File(temporary.root, "project-a/data/big8").exists())
    }

    @Test fun recoveryRefusesDestinationCreatedAfterInitialValidation() {
        val root = File(temporary.root.canonicalFile, "restore-race")
        val store = ProjectDataStore(root, TestFileOps())
        val commit: (() -> Unit) -> Unit = { it() }
        store.write("saved.txt", "original".toByteArray(), {}, commit)
        store.remove("saved.txt", {}, commit)
        val entry = File(root, ".trash").listFiles()!!.single()
        assertThrowsCode("FILE_EXISTS") {
            store.restore(entry.name, {}) { action ->
                File(root, "data/saved.txt").writeText("new file")
                action()
            }
        }
        assertEquals("new file", File(root, "data/saved.txt").readText())
        assertEquals("original", File(entry, "file").readText())
    }

    @Test fun recoveryRechecksQuotaInsideCommitAndKeepsRecoverableFile() {
        val root = File(temporary.root.canonicalFile, "restore-quota")
        val store = ProjectDataStore(root, TestFileOps())
        val commit: (() -> Unit) -> Unit = { it() }
        store.write("saved.txt", "original".toByteArray(), {}, commit)
        store.remove("saved.txt", {}, commit)
        val entry = File(root, ".trash").listFiles()!!.single()
        assertThrowsCode("FILE_QUOTA") {
            store.restore(entry.name, {}) { action ->
                repeat(8) { File(root, "data/fill$it.txt").writeBytes(ByteArray(65536) { 120 }) }
                action()
            }
        }
        assertFalse(File(root, "data/saved.txt").exists())
        assertEquals("original", File(entry, "file").readText())
    }

    @Test fun fileMoveRevalidatesChangedSourceInsideCommit() {
        val root = File(temporary.root.canonicalFile, "move-race")
        val store = ProjectDataStore(root, TestFileOps())
        store.write("source.txt", "original".toByteArray(), {}, { it() })
        assertThrowsCode("FILE_TOO_LARGE") {
            store.move("source.txt", "target.txt", {}) { action ->
                File(root, "data/source.txt").writeBytes(ByteArray(65537) { 120 })
                action()
            }
        }
        assertFalse(File(root, "data/target.txt").exists())
        assertTrue(File(root, "data/source.txt").exists())
    }

    @Test fun atomicFailureKeepsOldFileAndRemovesTemporaryOutput() {
        val ops = object : TestFileOps() {
            var failMove = false
            override fun moveAtomic(source: File, target: File) { if (failMove) throw IOException("private path and secret"); super.moveAtomic(source, target) }
        }
        val adapter = NativeDataFileAdapter(temporary.root, ops)
        assertTrue(adapter.configure("project-a", true))
        success(adapter.dispatch(1, 14101, "state", "old".toByteArray()))
        ops.failMove = true
        failure("FILE_UNAVAILABLE", adapter.dispatch(2, 14101, "state", "new".toByteArray()))
        assertEquals("old", success(adapter.dispatch(3, 14100, "state", byteArrayOf())))
        assertEquals(listOf("state"), File(temporary.root, "script-data/project-a/data").list()!!.toList())
    }

    @Test fun entryQuotaFailureDoesNotLeaveNewEmptyDirectoriesBeyondTheLimit() {
        // This is a quota/rollback test, not a host disk benchmark. The dedicated fake-clock
        // test below verifies the production 2s deadline; concurrent Windows builds vary wildly.
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps(), nanoTime = { 0L })
        assertTrue(adapter.configure("project-a", true))
        val directory = File(temporary.root, "script-data/project-a/data")
        for (index in 1..255) assertTrue(File(directory, "f$index").createNewFile())
        failure("FILE_QUOTA", adapter.dispatch(256, 14101, "new/nested/file", byteArrayOf()))
        assertFalse(File(temporary.root, "script-data/project-a/data/new").exists())
        success(adapter.dispatch(257, 14101, "last", byteArrayOf()))
        failure("FILE_QUOTA", adapter.dispatch(258, 14101, "overflow", byteArrayOf()))
        assertEquals(256, File(temporary.root, "script-data/project-a/data").list()!!.size)
    }

    @Test fun cancellationAndDeadlineBeforeCommitLeaveTheOldFileIntact() {
        val clock = AtomicLong(0)
        val hook = AtomicReference<(() -> Unit)?>(null)
        val ops = object : TestFileOps() {
            override fun outputExclusive(file: File): OutputStream = object : FilterOutputStream(super.outputExclusive(file)) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); hook.getAndSet(null)?.invoke() }
            }
        }
        val adapter = NativeDataFileAdapter(temporary.root, ops, clock::get)
        assertTrue(adapter.configure("project-a", true))
        success(adapter.dispatch(1, 14101, "state", "old".toByteArray()))
        hook.set { adapter.cancel(2) }
        failure("FILE_CANCELLED", adapter.dispatch(2, 14101, "state", ByteArray(20_000) { 120 }))
        assertEquals("old", success(adapter.dispatch(3, 14100, "state", byteArrayOf())))
        hook.set { clock.set(2_000_000_000) }
        failure("FILE_TIMEOUT", adapter.dispatch(4, 14101, "state", "new".toByteArray()))
        assertEquals("old", success(adapter.dispatch(5, 14100, "state", byteArrayOf())))
        adapter.cancel(6)
        failure("FILE_CANCELLED", adapter.dispatch(6, 14101, "state", "new".toByteArray()))
        assertEquals(listOf("state"), File(temporary.root, "script-data/project-a/data").list()!!.toList())
    }

    @Test fun switchingProjectOrClosingDuringWriteInvalidatesLateCommit() {
        val hook = AtomicReference<(() -> Unit)?>(null)
        val ops = object : TestFileOps() {
            override fun outputExclusive(file: File): OutputStream = object : FilterOutputStream(super.outputExclusive(file)) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); hook.getAndSet(null)?.invoke() }
            }
        }
        val adapter = NativeDataFileAdapter(temporary.root, ops)
        assertTrue(adapter.configure("project-a", true))
        success(adapter.dispatch(1, 14101, "state", "old".toByteArray()))
        hook.set { assertTrue(adapter.configure("project-b", true)) }
        failure("FILE_CANCELLED", adapter.dispatch(2, 14101, "state", "new".toByteArray()))
        failure("FILE_NOT_FOUND", adapter.dispatch(3, 14100, "state", byteArrayOf()))
        assertTrue(adapter.configure("project-a", true))
        assertEquals("old", success(adapter.dispatch(4, 14100, "state", byteArrayOf())))
        hook.set { adapter.close() }
        failure("FILE_CANCELLED", adapter.dispatch(5, 14101, "state", "new".toByteArray()))
        assertEquals("old", File(temporary.root, "script-data/project-a/data/state").readText())
    }

    @Test fun onlyOnePendingFileOperationIsAllowedAndStopCancelsTheBlockedRead() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ops = object : TestFileOps() {
            override fun input(file: File): InputStream { ready.countDown(); check(release.await(2, TimeUnit.SECONDS)); return super.input(file) }
        }
        val adapter = NativeDataFileAdapter(temporary.root, ops)
        assertTrue(adapter.configure("project-a", true))
        success(adapter.dispatch(1, 14101, "state", "old".toByteArray()))
        val result = AtomicReference<ByteArray>()
        val worker = Thread { result.set(adapter.dispatch(2, 14100, "state", byteArrayOf())) }.apply { start() }
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            failure("FILE_QUEUE_FULL", adapter.dispatch(3, 14102, "", byteArrayOf()))
            adapter.cancel(2)
        } finally { release.countDown(); worker.join(2_000) }
        assertFalse(worker.isAlive)
        failure("FILE_CANCELLED", result.get())
    }

    @Test fun symbolicLinksAreRejectedWithoutReadingOrWritingTheirTarget() {
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("private")
        val adapter = NativeDataFileAdapter(temporary.root, TestFileOps())
        assertTrue(adapter.configure("project-a", true))
        val link = File(temporary.root, "script-data/project-a/data/link")
        try { Files.createSymbolicLink(link.toPath(), outside.toPath()) } catch (error: Exception) { assumeNoException(error) }
        failure("FILE_PATH_INVALID", adapter.dispatch(1, 14100, "link/secret", byteArrayOf()))
        failure("FILE_PATH_INVALID", adapter.dispatch(2, 14101, "link/secret", "changed".toByteArray()))
        failure("FILE_PATH_INVALID", adapter.dispatch(3, 14102, "", byteArrayOf()))
        assertEquals("private", File(outside, "secret").readText())
    }

    @Test fun extendedFileCommandsPerformRealAtomicChangesAndTypedResults() {
        val adapter=NativeDataFileAdapter(temporary.root,TestFileOps())
        assertTrue(adapter.configure("project-a",true))
        var work=0L
        fun command(op: Int,path: String,vararg args: Any): String {
            val payload=com.google.gson.Gson().toJson(args.toList()).toByteArray()
            return success(adapter.dispatch(++work,op,path,payload))
        }
        assertEquals("0",command(14111,"missing/a.txt"))
        assertEquals("1",command(14113,"notes/new"))
        assertEquals("1",command(14112,"notes/new"))
        assertEquals("1",command(14116,"notes/new"))
        assertEquals("1",command(14110,"notes/a.txt","甲\r\n乙\n"))
        assertEquals("2",command(14120,"notes/a.txt"))
        assertEquals("\"乙\"",command(14117,"notes/a.txt",2))
        assertEquals("1",command(14118,"notes/a.txt",2,"😀"))
        assertEquals("1",command(14121,"notes/a.txt",1,"中文"))
        assertEquals("1",command(14119,"notes/a.txt",3))
        assertEquals("1",command(14114,"notes/a.txt","copy/a.txt"))
        assertEquals("1",command(14115,"copy/a.txt","moved.txt"))
        assertEquals("0",command(14111,"copy/a.txt"))
        assertEquals("1",command(14111,"moved.txt"))
        assertEquals("中文\n😀\n",success(adapter.dispatch(++work,14100,"moved.txt",byteArrayOf())))
        assertEquals("1",command(14122,"state/config.json","nested","{\"enabled\":true}"))
        assertEquals("\"{\\\"enabled\\\":true}\"",command(14123,"state/config.json","nested"))
        assertEquals("\"[\\\"nested\\\"]\"",command(14125,"state/config.json"))
        assertEquals("3",command(14126,"state/config.json","count",3))
        assertEquals("1",command(14126,"state/config.json","count",-2))
        assertEquals("1",command(14124,"state/config.json","nested"))
        assertEquals("0",command(14124,"state/config.json","nested"))
    }
    @Test fun extendedCommandsRejectOverwriteBadJsonIndicesAndNonemptyDirectories() {
        val adapter=NativeDataFileAdapter(temporary.root,TestFileOps())
        assertTrue(adapter.configure("project-a",true))
        success(adapter.dispatch(1,14101,"a","old".toByteArray()))
        success(adapter.dispatch(2,14101,"target","kept".toByteArray()))
        fun payload(vararg args: Any)=com.google.gson.Gson().toJson(args.toList()).toByteArray()
        failure("FILE_EXISTS",adapter.dispatch(3,14114,"a",payload("target")))
        failure("FILE_EXISTS",adapter.dispatch(4,14115,"a",payload("target")))
        failure("FILE_REQUEST_INVALID",adapter.dispatch(5,14118,"a",payload(0,"wrong")))
        failure("FILE_REQUEST_INVALID",adapter.dispatch(6,14118,"a",payload(1,"a\nb")))
        failure("FILE_REQUEST_INVALID",adapter.dispatch(7,14122,"kv",payload("key","{invalid}")))
        success(adapter.dispatch(8,14101,"folder/a","child".toByteArray()))
        failure("FILE_DIRECTORY_NOT_EMPTY",adapter.dispatch(9,14116,"folder",payload()))
        failure("FILE_PATH_INVALID",adapter.dispatch(10,14113,"../outside",payload()))
        assertEquals("old",success(adapter.dispatch(11,14100,"a",byteArrayOf())))
        assertEquals("kept",success(adapter.dispatch(12,14100,"target",byteArrayOf())))
        adapter.cancel(13)
        failure("FILE_CANCELLED",adapter.dispatch(13,14110,"a",payload("late")))
        assertEquals("old",success(adapter.dispatch(14,14100,"a",byteArrayOf())))
    }

    private fun success(reply: ByteArray): String { assertTrue(reply.isNotEmpty()); assertEquals(0, reply[0].toInt()); return reply.copyOfRange(1, reply.size).toString(Charsets.UTF_8) }
    private fun failure(code: String, reply: ByteArray) { assertArrayEquals(byteArrayOf(1) + code.toByteArray(Charsets.US_ASCII), reply) }
    private fun assertThrowsCode(code: String, block: () -> Unit) { try { block(); fail("Expected $code") } catch (error: ProjectDataFailure) { assertEquals(code, error.code) } }
}
