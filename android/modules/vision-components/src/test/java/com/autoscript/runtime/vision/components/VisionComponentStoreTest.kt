package com.autoscript.runtime.vision.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class VisionComponentStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(kind: String = "opencv") = File(requireNotNull(System.getProperty("vision.components.$kind")))
    private fun trust(): Set<String> {
        val store = KeyStore.getInstance("JKS")
        File(requireNotNull(System.getProperty("vision.publisher.keystore"))).inputStream().use { store.load(it, "android".toCharArray()) }
        return setOf(MessageDigest.getInstance("SHA-256").digest(requireNotNull(store.getCertificate("androiddebugkey")).encoded).hex())
    }
    private fun store(root: File, certificates: Set<String> = trust(), seal: (File) -> Unit = {}, unseal: (File) -> Unit = {}) = VisionComponentStore(root, certificates,
        publishSelection = { source, target -> java.nio.file.Files.move(source.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); Unit },
        sealDirectory = seal, unsealDirectory = unseal) { file ->
        // JVM manifest fixture only; production uses AndroidVisionComponents.store.
        val descriptor = ZipFile(file).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry(VisionComponentDescriptor.PATH))).use { VisionComponentDescriptor.parse(it.readBytes()) }
        }
        VisionComponentManifest(descriptor.kind.applicationId, 1, descriptor.kind.sdkVersion, 24, true, 0, 0)
    }
    private fun import(store: VisionComponentStore, kind: String = "opencv") = store.import({ fixture(kind).inputStream() }, {})

    @Test fun importIsNotSelectionAndExactSelectionNeverFallsBackAfterRemoval() {
        val root = temporary.newFolder(); val store = store(root)
        val cv = import(store); val onnx = import(store, "onnx")
        assertEquals(null, store.selected(VisionComponentKind.OPENCV, {}))
        store.select(VisionComponentKind.OPENCV, cv.sha256, {})
        assertEquals(cv.sha256, store.selectionId(VisionComponentKind.OPENCV, {}))
        assertEquals(cv, store.selected(VisionComponentKind.OPENCV, {}))
        assertThrows(IllegalArgumentException::class.java) { store.select(VisionComponentKind.OPENCV, onnx.sha256, {}) }
        assertEquals(cv, store.selected(VisionComponentKind.OPENCV, {}))
        store.select(VisionComponentKind.OPENCV, cv.sha256, {}) // Atomic replacement, including Windows host tests.
        store.remove(cv.sha256, {})
        assertThrows(VisionComponentSelectionMissing::class.java) { store.selected(VisionComponentKind.OPENCV, {}) }
        assertTrue(File(root, "${onnx.sha256}.apk").isFile)
        store.clearSelection(VisionComponentKind.OPENCV, {})
        assertEquals(null, store.selected(VisionComponentKind.OPENCV, {}))
    }

    @Test fun cancelledSelectionDoesNotPublishAndCorruptSelectionCannotChooseAnotherArtifact() {
        val root = temporary.newFolder(); val store = store(root); val cv = import(store)
        assertThrows(CancellationException::class.java) { store.select(VisionComponentKind.OPENCV, cv.sha256) {
            if (root.listFiles().orEmpty().any { it.name.startsWith("selection-") }) throw CancellationException()
        } }
        assertEquals(null, store.selected(VisionComponentKind.OPENCV, {}))
        File(root, "opencv.selection").writeText("v1\n" + "X".repeat(64) + "\n")
        assertThrows(IllegalArgumentException::class.java) { store.selected(VisionComponentKind.OPENCV, {}) }
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("selection-") })
        assertTrue(File(root, "${cv.sha256}.apk").isFile)
    }

    @Test fun missingProcessAbiIsExplicitlyRejectedWithoutAnAlgorithmFallback() {
        val descriptor = VisionComponentDescriptor(VisionComponentKind.OPENCV, setOf("arm64-v8a"))
        VisionComponentStore.checkProcessAbi(descriptor, "arm64-v8a")
        assertThrows(VisionComponentDeviceUnsupported::class.java) { VisionComponentStore.checkProcessAbi(descriptor, "x86_64") }
        assertThrows(IllegalArgumentException::class.java) { VisionComponentStore.checkProcessAbi(descriptor, "armeabi-v7a") }
    }

    @Test fun destinationIsSealedBeforeTheFirstExecutableByteIsRead() {
        val target = File(temporary.newFolder(), "component.apk")
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertTrue(target.exists())
                assertFalse(target.canWrite())
                return super.read(buffer, offset, length)
            }
        }
        assertEquals(3L, VisionComponentStore.copyReadOnly(input, target, 3, 3, {}).first)
        VisionComponentStore.deleteOwnedFile(target)
    }

    @Test fun eighthSlotLimitNeverDeletesExistingInvalidOrUnknownArtifacts() {
        val root = temporary.newFolder()
        val existing = (1..8).map { n -> File(root, n.toString(16).padStart(64, '0') + ".apk").apply { writeBytes(byteArrayOf(1)) } }
        assertThrows(IllegalArgumentException::class.java) { import(store(root)) }
        assertTrue(existing.all { it.isFile && it.readBytes().contentEquals(byteArrayOf(1)) })
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("incoming-") })
    }

    @Test fun importIsReadOnlyContentAddressedAndDuplicateDoesNotOverwrite() {
        val root = temporary.newFolder()
        val store = store(root)
        val first = import(store)
        val file = File(root, "${first.sha256}.apk")
        assertFalse(file.canWrite())
        val modified = file.lastModified()
        assertEquals(first, import(store))
        assertEquals(modified, file.lastModified())
        val inventory = store.inventory {}
        assertEquals(1, inventory.size)
        assertEquals(StoredVisionComponentState.READY, inventory.single().state)
        assertEquals(first, inventory.single().verified)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("incoming-") })
    }

    @Test fun preparationHasIndependentReadOnlyApkAndExactOneAbiLibraries() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {}).use { prepared ->
            prepared.verify {}
            assertEquals(imported, prepared.verified)
            assertFalse(prepared.apk.canWrite())
            assertEquals(VisionComponentKind.OPENCV.nativeLibraries, prepared.nativeDirectory.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(prepared.nativeDirectory.listFiles().orEmpty().all { !it.canWrite() })
            assertEquals(null, prepared.model)
            assertTrue(prepared.apk != File(root, "${imported.sha256}.apk"))
            store.remove(imported.sha256) {}
            prepared.verify {} // Deleting the import cannot invalidate an owned frozen snapshot.
        }
        assertTrue(File(root, "prepared").listFiles().orEmpty().isEmpty())
    }

    @Test fun publicationSealsOwnedDirectoriesAndCancellationUnsealsBeforeExactCleanup() {
        val root = temporary.newFolder()
        val sealed = mutableListOf<File>(); val unsealed = mutableListOf<File>()
        val store = store(root, seal = { directory ->
            assertTrue(directory.isDirectory)
            sealed += directory
        }, unseal = { directory -> assertTrue(directory.isDirectory); unsealed += directory })
        val imported = import(store)
        assertThrows(CancellationException::class.java) {
            store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a") {
                if (sealed.size == 2) throw CancellationException()
            }
        }
        assertEquals("native", sealed.first().name)
        assertEquals(sealed.reversed(), unsealed)
        assertTrue(File(root, "prepared").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "${imported.sha256}.apk").isFile)
    }

    @Test fun closingOwnerDoesNotDeleteSnapshotWhileProviderLeaseIsHeld() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        val lease = prepared.retain()
        prepared.close()
        assertTrue(prepared.apk.exists())
        prepared.verifyRetained {}
        lease.close()
        lease.close()
        assertTrue(File(root, "prepared").listFiles().orEmpty().isEmpty())
        assertThrows(IllegalStateException::class.java) { prepared.verifyRetained {} }
    }

    @Test fun onnxPreparationHasOnlyPinnedModelAndItsTwoLibraries() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store, "onnx")
        store.prepare(imported.sha256, VisionComponentKind.ONNX, "arm64-v8a", {}).use { prepared ->
            assertEquals(7_653_044L, requireNotNull(prepared.model).length())
            assertFalse(requireNotNull(prepared.model).canWrite())
            assertEquals(VisionComponentKind.ONNX.nativeLibraries, prepared.nativeDirectory.listFiles().orEmpty().map { it.name }.toSet())
            prepared.verify {}
        }
    }

    @Test fun twoPreparedLeasesAreBoundedAndClosingReclaimsTheOwnedSlot() {
        val store = store(temporary.newFolder())
        val imported = import(store)
        val first = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        val second = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        try {
            assertTrue(first.apk != second.apk)
            assertThrows(IllegalArgumentException::class.java) { store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {}) }
            first.close()
            assertFalse(first.apk.exists())
            assertThrows(IllegalStateException::class.java) { first.verify {} }
            store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {}).use { it.verify {} }
            second.verify {}
        } finally { first.close(); second.close() }
    }

    @Test fun cancelledImportClosesInputAndOnlyDeletesItsStaging() {
        val root = temporary.newFolder()
        val marker = File(root, "unknown.keep").apply { writeText("preserve") }
        val store = store(root)
        var closed = false
        assertThrows(CancellationException::class.java) {
            store.import({ object : FilterInputStream(fixture().inputStream()) {
                override fun close() { closed = true; super.close() }
            } }) {
                if (root.listFiles().orEmpty().any { it.name.startsWith("incoming-") }) throw CancellationException()
            }
        }
        assertTrue(closed)
        assertEquals("preserve", marker.readText())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("incoming-") })
        assertTrue(store.inventory {}.isEmpty())
    }

    @Test fun wrongPublisherImportDoesNotPublishOrDeleteUnknownFiles() {
        val root = temporary.newFolder()
        val marker = File(root, "keep").apply { writeText("preserve") }
        assertThrows(IllegalArgumentException::class.java) { import(store(root, setOf("0".repeat(64)))) }
        assertEquals("preserve", marker.readText())
        assertFalse(root.listFiles().orEmpty().any { it.name.matches(Regex("[0-9a-f]{64}\\.apk")) || it.name.startsWith("incoming-") })
    }

    @Test fun cancellationDuringExtractionCleansOnlyCurrentSnapshot() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val original = File(root, "${imported.sha256}.apk")
        assertThrows(CancellationException::class.java) {
            store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a") {
                if (File(root, "prepared").listFiles().orEmpty().any { File(it, "native").exists() }) throw CancellationException()
            }
        }
        assertTrue(original.isFile)
        assertTrue(File(root, "prepared").listFiles().orEmpty().isEmpty())
        assertEquals(StoredVisionComponentState.READY, store.inventory {}.single().state)
    }

    @Test fun wrongKindAndEscapingIdsNeverCreateSnapshotsOrRemoveUnknownFile() {
        val root = temporary.newFolder()
        val marker = File(root, "keep").apply { writeText("preserve") }
        val store = store(root)
        val imported = import(store)
        assertThrows(IllegalArgumentException::class.java) { store.prepare(imported.sha256, VisionComponentKind.ONNX, "arm64-v8a", {}) }
        assertThrows(IllegalArgumentException::class.java) { store.remove("../keep") {} }
        assertEquals("preserve", marker.readText())
        assertFalse(File(root, "prepared").exists())
    }

    @Test fun heldRegistryLockReturnsBusyWithoutWaiting() {
        val root = temporary.newFolder()
        val store = store(root)
        RandomAccessFile(File(root, "registry.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                val start = System.nanoTime()
                assertThrows(IllegalStateException::class.java) { store.inventory {} }
                assertTrue(System.nanoTime() - start < 1_000_000_000L)
            }
        }
        assertTrue(store.inventory {}.isEmpty())
    }

    @Test fun anotherProcessHoldingRegistryLockIsProtectedWithoutWaiting() {
        val root = temporary.newFolder()
        val store = store(root)
        val classpath = listOf(VisionRegistryLeaseProcess::class.java, kotlin.Unit::class.java)
            .joinToString(File.pathSeparator) { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
        val java = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"}")
        val helper = ProcessBuilder(java.path, "-cp", classpath, VisionRegistryLeaseProcess::class.java.name,
            File(root, "registry.lock").path).redirectErrorStream(true).start()
        try {
            val reader = helper.inputStream.bufferedReader()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!reader.ready() && helper.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Registry helper did not start", reader.ready())
            assertEquals("READY", reader.readLine())
            val start = System.nanoTime()
            assertThrows(IllegalStateException::class.java) { store.inventory {} }
            assertTrue(System.nanoTime() - start < 1_000_000_000L)
            helper.outputStream.write(10); helper.outputStream.flush()
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue())
        } finally { if (helper.isAlive) { helper.destroyForcibly(); helper.waitFor(2, TimeUnit.SECONDS) } }
        assertTrue(store.inventory {}.isEmpty())
    }

    @Test fun writableOrTamperedArtifactIsCorruptAndRemainsForExplicitDeletion() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val file = File(root, "${imported.sha256}.apk")
        assertTrue(file.setWritable(true))
        assertEquals(StoredVisionComponentState.CORRUPT, store.inventory {}.single().state)
        assertTrue(file.exists())
        store.remove(imported.sha256) {}
        assertFalse(file.exists())
    }

    @Test fun preparedTamperingAndLateUseAreRejected() {
        val store = store(temporary.newFolder())
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        try {
            val file = requireNotNull(prepared.nativeDirectory.listFiles()).first()
            assertTrue(file.setWritable(true))
            RandomAccessFile(file, "rw").use { it.seek(10); val byte = it.read(); it.seek(10); it.write(byte xor 1) }
            assertTrue(file.setReadOnly())
            assertThrows(IllegalArgumentException::class.java) { prepared.verify {} }
        } finally { prepared.close() }
        assertThrows(IllegalStateException::class.java) { prepared.verify {} }
    }

    @Test fun closePreservesUnknownSnapshotFileAndCanRetryAfterItsOwnerRemovesIt() {
        val store = store(temporary.newFolder())
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        val marker = File(prepared.apk.parentFile, "unknown.keep").apply { writeText("preserve") }
        assertThrows(IllegalArgumentException::class.java) { prepared.close() }
        assertEquals("preserve", marker.readText())
        assertTrue(prepared.apk.isFile)
        assertEquals(1, store.recoverPrepared {}) // The refused cleanup still holds its original lease.
        assertTrue(marker.delete()) // Test-owned fixture only.
        prepared.close()
        prepared.close()
        assertFalse(requireNotNull(prepared.apk.parentFile).exists())
    }

    @Test fun recoveryNeverDeletesUnknownNativeSnapshotContent() {
        val root = temporary.newFolder()
        val snapshot = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000").also { it.mkdirs() }
        File(snapshot, "component.apk").writeText("partial")
        val native = File(snapshot, "native").also { it.mkdirs() }
        File(native, "libopencv_java4.so").writeText("partial")
        val unknown = File(native, "keep.bin").apply { writeText("preserve") }
        store(root).recoverPrepared {}
        assertTrue(snapshot.exists())
        assertEquals("preserve", unknown.readText())
    }

    @Test fun prepareRecoversOwnedInterruptedSnapshotBeforeApplyingSlotLimit() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val snapshot = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000").also { it.mkdirs() }
        File(snapshot, "component.apk").writeText("partial")
        File(snapshot, "native").also { it.mkdirs() }
            .resolve("libopencv_java4.so").writeText("partial")
        store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {}).use { it.verify {} }
        assertTrue(File(root, "prepared").listFiles().orEmpty().isEmpty())
    }

    @Test fun recoveryNeverDeletesOrHashesALiveTamperedSnapshot() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        val native = requireNotNull(prepared.nativeDirectory.listFiles()).first()
        assertTrue(native.setWritable(true))
        native.appendBytes(byteArrayOf(1))
        assertTrue(native.setReadOnly())
        assertEquals(1, store.recoverPrepared {})
        assertTrue(requireNotNull(prepared.apk.parentFile).exists())
        assertThrows(IllegalArgumentException::class.java) { prepared.verify {} }
        prepared.close()
    }

    @Test fun recoveryReclaimsCompletedSnapshotsOnlyAfterTheirOwnershipEnds() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        try {
            val stale = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000")
            // An independent copy models files remaining after their process died.
            assertTrue(stale.mkdir())
            assertTrue(File(stale, "native").mkdir())
            val original = requireNotNull(prepared.apk.parentFile)
            // Windows denies copying a live exclusive lock file; executable bytes
            // are copied independently and the simulated dead owner's lock is new.
            original.walkTopDown().filter { it.isFile && it.name != VisionSnapshotOwnership.FILE_NAME }.forEach {
                val copy = File(stale, it.relativeTo(original).path)
                it.copyTo(copy)
                assertTrue(copy.setReadOnly())
            }
            assertTrue(File(stale, VisionSnapshotOwnership.FILE_NAME).createNewFile())
            assertEquals(1, store.recoverPrepared {})
            assertFalse(stale.exists())
            prepared.verify {}
        } finally { prepared.close() }
    }

    @Test fun recoveryPreservesLegacyCompletedSnapshotsWithoutOwnershipEvidence() {
        val root = temporary.newFolder()
        val snapshot = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000").also { it.mkdirs() }
        File(snapshot, "component.apk").writeText("damaged legacy copy")
        File(snapshot, "complete.marker").apply { createNewFile(); setReadOnly() }
        assertEquals(0, store(root).recoverPrepared {})
        assertTrue(snapshot.exists())
    }

    @Test fun recoveryKeepsUnknownFilesInsideAnUnlockedOwnedSnapshot() {
        val root = temporary.newFolder()
        val snapshot = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000").also { it.mkdirs() }
        File(snapshot, VisionSnapshotOwnership.FILE_NAME).createNewFile()
        File(snapshot, "component.apk").writeText("partial")
        val marker = File(snapshot, "unknown.keep").apply { writeText("preserve") }
        store(root).recoverPrepared {}
        assertEquals("preserve", marker.readText())
        assertTrue(File(snapshot, "component.apk").exists())
    }

    @Test fun busyRegistryDuringCloseKeepsOwnershipAndAllowsExplicitRetry() {
        val root = temporary.newFolder()
        val store = store(root)
        val imported = import(store)
        val prepared = store.prepare(imported.sha256, VisionComponentKind.OPENCV, "arm64-v8a", {})
        RandomAccessFile(File(root, "registry.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                assertThrows(IllegalStateException::class.java) { prepared.close() }
                assertTrue(prepared.apk.exists())
            }
        }
        assertEquals(1, store.recoverPrepared {})
        prepared.close()
        assertFalse(requireNotNull(prepared.apk.parentFile).exists())
    }

    @Test fun independentProcessLeaseProtectsSnapshotUntilHelperExits() {
        val root = temporary.newFolder()
        val store = store(root)
        val snapshot = File(root, "prepared/snapshot-123e4567-e89b-12d3-a456-426614174000").also { it.mkdirs() }
        File(snapshot, "component.apk").writeText("partial")
        val ownershipFile = File(snapshot, VisionSnapshotOwnership.FILE_NAME).apply { createNewFile() }
        val classpath = listOf(VisionRegistryLeaseProcess::class.java, kotlin.Unit::class.java)
            .joinToString(File.pathSeparator) { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }
        val java = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"}")
        val helper = ProcessBuilder(java.path, "-cp", classpath, VisionRegistryLeaseProcess::class.java.name,
            ownershipFile.path).redirectErrorStream(true).start()
        try {
            val reader = helper.inputStream.bufferedReader()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!reader.ready() && helper.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Snapshot helper did not start", reader.ready())
            assertEquals("READY", reader.readLine())
            val start = System.nanoTime()
            assertEquals(1, store.recoverPrepared {})
            assertTrue(System.nanoTime() - start < 1_000_000_000L)
            assertTrue(snapshot.exists())
            helper.outputStream.write(10); helper.outputStream.flush()
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue())
        } finally { if (helper.isAlive) { helper.destroyForcibly(); helper.waitFor(2, TimeUnit.SECONDS) } }
        assertEquals(0, store.recoverPrepared {})
        assertFalse(snapshot.exists())
    }
}
