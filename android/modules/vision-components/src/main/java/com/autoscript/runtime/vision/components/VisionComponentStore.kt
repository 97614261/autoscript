package com.autoscript.runtime.vision.components

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile

enum class StoredVisionComponentState { READY, INCOMPATIBLE, CORRUPT }
data class StoredVisionComponent(val id: String, val state: StoredVisionComponentState, val verified: VerifiedVisionComponent?)
class VisionComponentDeviceUnsupported : IllegalArgumentException("VISION_COMPONENT_DEVICE_UNSUPPORTED")
class VisionComponentSelectionMissing : IllegalStateException("VISION_COMPONENT_SELECTED_ARTIFACT_MISSING")

/** Host-only private immutable artifacts. All calls belong on bounded IO tasks. */
class VisionComponentStore(
    private val directory: File,
    private val trustedCertificates: Set<String>,
    private val publishSelection: (File, File) -> Unit,
    private val sealDirectory: (File) -> Unit = {},
    private val unsealDirectory: (File) -> Unit = {},
    private val manifestReader: (File) -> VisionComponentManifest,
) {
    /** Import does not select. A missing exact selection never chooses another APK. */
    fun selected(kind: VisionComponentKind, checkpoint: () -> Unit): VerifiedVisionComponent? = locked(checkpoint) {
        val id = readSelectionIdLocked(kind) ?: return@locked null
        val artifact = child("$id.apk")
        if (!artifact.isFile) throw VisionComponentSelectionMissing()
        require(!artifact.canWrite())
        val checked = validate(artifact, checkpoint)
        require(checked.sha256 == id && checked.descriptor.kind == kind)
        checked
    }

    /** Inventory UI may inspect the bounded record; this does not verify or load its artifact. */
    fun selectionId(kind: VisionComponentKind, checkpoint: () -> Unit): String? = locked(checkpoint) { readSelectionIdLocked(kind) }

    private fun readSelectionIdLocked(kind: VisionComponentKind): String? {
        val selection = child("${kind.id}.selection")
        if (!selection.exists()) return null
        require(selection.isFile && selection.length() == 68L)
        val text = selection.readText(Charsets.US_ASCII)
        require(text.startsWith("v1\n") && text.endsWith('\n'))
        val id = text.substring(3, 67)
        require(id.matches(ID))
        return id
    }

    /** Only a fully validated fixed artifact can be explicitly selected. */
    fun select(kind: VisionComponentKind, id: String, checkpoint: () -> Unit) = locked(checkpoint) {
        require(id.matches(ID))
        val artifact = child("$id.apk")
        require(artifact in contents() && artifact.isFile && !artifact.canWrite())
        val checked = validate(artifact, checkpoint)
        require(checked.sha256 == id && checked.descriptor.kind == kind)
        val pending = child("selection-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(pending).use { output ->
                output.write("v1\n$id\n".toByteArray(Charsets.US_ASCII)); output.fd.sync()
            }
            checkpoint()
            publishSelection(pending, child("${kind.id}.selection"))
        } finally { if (pending.exists()) deleteOwnedFile(pending) }
    }

    fun clearSelection(kind: VisionComponentKind, checkpoint: () -> Unit) = locked(checkpoint) {
        val file = child("${kind.id}.selection")
        if (file.exists()) {
            require(file.isFile && file.length() <= 128)
            checkpoint(); deleteOwnedFile(file)
        }
    }

    fun inventory(checkpoint: () -> Unit): List<StoredVisionComponent> = locked(checkpoint) {
        contents().filter { it.name.matches(APK_NAME) }.map { file ->
            checkpoint()
            try {
                val checked = validate(file, checkpoint)
                require(file.name == "${checked.sha256}.apk" && !file.canWrite())
                StoredVisionComponent(checked.sha256, StoredVisionComponentState.READY, checked)
            } catch (error: Exception) {
                if (error is CancellationException || error is InterruptedException) throw error
                checkpoint()
                StoredVisionComponent(file.name.removeSuffix(".apk"),
                    if (error is VisionComponentCompatibilityException) StoredVisionComponentState.INCOMPATIBLE else StoredVisionComponentState.CORRUPT, null)
            }
        }
    }

    fun import(open: () -> InputStream, checkpoint: () -> Unit): VerifiedVisionComponent = locked(checkpoint) {
        contents()
        val incoming = child("incoming-${UUID.randomUUID()}.apk")
        var published = false
        try {
            open().use { input -> copyReadOnly(input, incoming, VisionComponentValidator.MAX_APK_BYTES, null, checkpoint) }
            val checked = validate(incoming, checkpoint)
            val target = child("${checked.sha256}.apk")
            if (target.exists()) {
                require(!target.canWrite() && validate(target, checkpoint) == checked)
                return@locked checked
            }
            val files = contents().filter { it != incoming && it.name != LOCK_NAME && it.name != PREPARED_NAME }
            require(files.count { it.name.matches(APK_NAME) } < MAX_APKS)
            require(files.sumOf { it.length() } + checked.bytes <= MAX_STORED_BYTES)
            checkpoint()
            require(incoming.renameTo(target)) { "VISION_COMPONENT_PUBLISH_FAILED" }
            published = true
            checked
        } finally {
            if (!published && incoming.exists()) deleteOwnedFile(incoming)
        }
    }

    fun remove(id: String, checkpoint: () -> Unit) = locked(checkpoint) {
        require(id.matches(ID))
        val file = child("$id.apk")
        require(file in contents() && file.isFile)
        checkpoint()
        // Caller must obtain explicit confirmation. Unknown file names cannot be deleted.
        deleteOwnedFile(file)
    }

    fun prepare(id: String, kind: VisionComponentKind, processAbi: String, checkpoint: () -> Unit): PreparedVisionComponent = locked(checkpoint) {
        require(id.matches(ID))
        val source = child("$id.apk")
        if (!source.isFile) throw VisionComponentSelectionMissing()
        require(source in contents() && source.isFile && !source.canWrite())
        val checked = validate(source, checkpoint)
        require(checked.sha256 == id && checked.descriptor.kind == kind)
        checkProcessAbi(checked.descriptor, processAbi)
        val preparedRoot = child(PREPARED_NAME)
        require(preparedRoot.mkdirs() || preparedRoot.isDirectory)
        require(preparedRoot.canonicalFile == preparedRoot.absoluteFile)
        // Recover owned interrupted snapshots before enforcing the two-slot limit.
        recoverPreparedLocked(preparedRoot, checkpoint)
        val prepared = requireNotNull(preparedRoot.listFiles()).toList()
        require(prepared.size < MAX_PREPARED && prepared.all { it.canonicalFile == it.absoluteFile && it.isDirectory && it.name.matches(SNAPSHOT_NAME) })
        val existingBytes = prepared.sumOf { snapshotBytes(it) }
        val nativeNames = kind.nativeLibraries.map { "lib/$processAbi/$it" }
        val models = checked.descriptor.models.associateBy { it.path }
        val requiredBytes = ZipFile(source).use { zip ->
            checked.bytes + (nativeNames + models.keys).sumOf { requireNotNull(zip.getEntry(it)).size }
        }
        require(existingBytes + requiredBytes <= MAX_PREPARED_BYTES)
        require(preparedRoot.usableSpace >= requiredBytes + SPACE_RESERVE)
        val snapshot = File(preparedRoot, "snapshot-${UUID.randomUUID()}")
        require(snapshot.mkdir() && snapshot.canonicalFile == snapshot.absoluteFile)
        val ownedFiles = mutableListOf<File>()
        val ownedDirectories = mutableListOf(snapshot)
        var ownership: VisionSnapshotOwnership? = null
        try {
            val snapshotOwner = VisionSnapshotOwnership.create(snapshot).also { ownership = it }
            val apk = File(snapshot, "component.apk").also { ownedFiles.add(it) }
            source.inputStream().use { copyReadOnly(it, apk, checked.bytes, checked.bytes, checkpoint) }
            require(validate(apk, checkpoint) == checked)
            val nativeDirectory = File(snapshot, "native").also { require(it.mkdir()); ownedDirectories.add(it) }
            val digests = linkedMapOf<File, Pair<Long, String>>()
            digests[apk] = checked.bytes to checked.sha256
            var model: File? = null
            var candidateModel: File? = null
            ZipFile(apk).use { zip ->
                for (name in nativeNames + models.keys) {
                    checkpoint()
                    val entry = requireNotNull(zip.getEntry(name))
                    val target = if (name in models) File(snapshot, models.getValue(name).snapshotName).also {
                        if (name == VisionComponentDescriptor.MODEL_PATH) model = it else candidateModel = it
                    }
                        else File(nativeDirectory, name.substringAfterLast('/'))
                    ownedFiles.add(target)
                    digests[target] = zip.getInputStream(entry).use { copyReadOnly(it, target, entry.size, entry.size, checkpoint) }
                }
            }
            val marker = File(snapshot, COMPLETE_NAME)
            ownedFiles.add(marker)
            val result = PreparedVisionComponent(checked, processAbi, snapshot, apk, nativeDirectory, model, candidateModel, digests,
                snapshotOwner) {
                locked({}) { cleanupOwnedSnapshot(ownedFiles.toList(), ownedDirectories.toList(), snapshotOwner) }
            }
            result.verify(checkpoint)
            // Publish completion only after every frozen byte has been verified.
            require(marker.createNewFile())
            require(marker.setReadOnly() && !marker.canWrite())
            // Frozen code must not acquire ART-generated oat/cache children during class loading.
            ownedDirectories.asReversed().forEach(sealDirectory)
            checkpoint()
            result
        } catch (error: Throwable) {
            try {
                val owner = ownership
                if (owner != null) cleanupOwnedSnapshot(ownedFiles, ownedDirectories, owner)
                else cleanupSnapshot(listOf(File(snapshot, VisionSnapshotOwnership.FILE_NAME)), ownedDirectories)
            } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun validate(file: File, checkpoint: () -> Unit) = VisionComponentValidator.validate(file, trustedCertificates, manifestReader, checkpoint)

    private fun cleanupOwnedSnapshot(files: List<File>, directories: List<File>, owner: VisionSnapshotOwnership) {
        if (owner.isHeld()) owner.verify()
        else check(files.none { it.exists() }) { "VISION_COMPONENT_LEASE_CLOSED" }
        val known = (files + directories + owner.file).toSet()
        // Refuse BEFORE deleting any executable bytes or releasing the lease. A later owner
        // may remove its unknown file, after which this same bounded close can safely retry.
        directories.filter { it.exists() }.forEach { dir ->
            require(dir.canonicalFile == dir.absoluteFile && dir.isDirectory)
            require(requireNotNull(dir.listFiles()).all { it in known && it.canonicalFile == it.absoluteFile })
        }
        directories.filter { it.exists() }.forEach { dir ->
            require(dir.canonicalFile == dir.absoluteFile && dir.isDirectory)
            unsealDirectory(dir)
        }
        // Keep liveness protection while executable bytes are being deleted.
        cleanupSnapshot(files, emptyList())
        owner.close()
        cleanupSnapshot(listOf(owner.file), directories)
    }

    /** Reclaim recognizable unowned transactions; live and unknown contents are retained. */
    fun recoverPrepared(checkpoint: () -> Unit): Int = locked(checkpoint) {
        val root = child(PREPARED_NAME)
        if (!root.exists()) return@locked 0
        require(root.isDirectory && root.canonicalFile == root.absoluteFile)
        recoverPreparedLocked(root, checkpoint)
    }

    private fun recoverPreparedLocked(root: File, checkpoint: () -> Unit): Int {
        var retained = 0
        val snapshots = requireNotNull(root.listFiles()).toList()
        require(snapshots.size <= 32)
        for (snapshot in snapshots.filter { it.name.matches(SNAPSHOT_NAME) }) {
            checkpoint()
            if (snapshot.canonicalFile != snapshot.absoluteFile || !snapshot.isDirectory) continue
            val ownershipFile = File(snapshot, VisionSnapshotOwnership.FILE_NAME)
            if (ownershipFile.exists()) {
                // Never read/hash/delete executable bytes owned by another live process.
                if (ownershipFile.canonicalFile != ownershipFile.absoluteFile || !ownershipFile.isFile || ownershipFile.length() != 0L) continue
                val owner = VisionSnapshotOwnership.acquire(ownershipFile)
                if (owner == null) {
                    retained++
                    continue
                }
                owner.use { cleanupRecognizedSnapshot(snapshot, it) }
                continue
            }
            val marker = File(snapshot, COMPLETE_NAME)
            if (marker.exists()) {
                // Legacy completed copies lack cross-process ownership evidence.
                // Report only valid ones; never infer that invalid means unused.
                if (isCompleteSnapshotValid(snapshot, checkpoint)) retained++
                continue
            }
            cleanupRecognizedSnapshot(snapshot, null)
        }
        return retained
    }

    /** Rechecks every frozen byte before a completed snapshot can consume a slot. */
    private fun isCompleteSnapshotValid(snapshot: File, checkpoint: () -> Unit): Boolean {
        return try {
            val children = requireNotNull(snapshot.listFiles()).toList()
            val apk = File(snapshot, "component.apk")
            if (children.any { it.canonicalFile != it.absoluteFile } || !apk.isFile || apk.canWrite()) return false
            val verified = validate(apk, checkpoint)
            val expectedNames = mutableSetOf("component.apk", "native", COMPLETE_NAME)
            expectedNames += verified.descriptor.models.map { it.snapshotName }
            if (children.map { it.name }.toSet() != expectedNames) return false
            val marker = File(snapshot, COMPLETE_NAME)
            if (!marker.isFile || marker.canWrite()) return false

            val native = File(snapshot, "native")
            val nativeFiles = requireNotNull(native.listFiles()).toList()
            if (!native.isDirectory || nativeFiles.map { it.name }.toSet() != verified.descriptor.kind.nativeLibraries ||
                nativeFiles.any { it.canonicalFile != it.absoluteFile || !it.isFile || it.canWrite() }) return false

            // The snapshot does not store the ABI separately; match the extracted
            // bytes against exactly one descriptor ABI instead of trusting names.
            val matchingAbi = verified.descriptor.abis.any { abi ->
                ZipFile(apk).use { zip ->
                    verified.descriptor.kind.nativeLibraries.all { name ->
                        val entry = zip.getEntry("lib/$abi/$name") ?: return@all false
                        val file = File(native, name)
                        file.length() == entry.size &&
                            file.inputStream().use { VisionComponentValidator.hash(it, entry.size, checkpoint) }.second ==
                            zip.getInputStream(entry).use { VisionComponentValidator.hash(it, entry.size, checkpoint) }.second
                    }
                }
            }
            if (!matchingAbi) return false

            for (fixed in verified.descriptor.models) {
                val model = File(snapshot, fixed.snapshotName)
                if (!model.isFile || model.canWrite()) return false
                ZipFile(apk).use { zip ->
                    val entry = requireNotNull(zip.getEntry(fixed.path))
                    if (model.length() != entry.size) return false
                    val actual = model.inputStream().use { VisionComponentValidator.hash(it, entry.size, checkpoint) }.second
                    val expected = zip.getInputStream(entry).use { VisionComponentValidator.hash(it, entry.size, checkpoint) }.second
                    if (actual != expected) return false
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException || error is InterruptedException) throw error
            false
        }
    }

    /** Deletes only a snapshot whose top-level shape is fully owned by us. */
    private fun cleanupRecognizedSnapshot(snapshot: File, ownership: VisionSnapshotOwnership?) {
        val children = snapshot.listFiles()?.toList() ?: return
        val allowed = setOf("component.apk", "native", "model.onnx", "model-v5.onnx", COMPLETE_NAME) +
            if (ownership != null) setOf(VisionSnapshotOwnership.FILE_NAME) else emptySet()
        if (children.any { it.canonicalFile != it.absoluteFile || it.name !in allowed ||
                if (it.name == "native") !it.isDirectory else !it.isFile }) return
        val native = File(snapshot, "native")
        val nativeFiles = if (native.exists()) native.listFiles()?.toList() ?: return else emptyList()
        if (nativeFiles.any { it.canonicalFile != it.absoluteFile || !it.isFile ||
                it.name !in VisionComponentKind.entries.flatMap { kind -> kind.nativeLibraries } }) return
        val files = children.filter { it.isFile && it.name != VisionSnapshotOwnership.FILE_NAME } + nativeFiles
        val directories = listOf(snapshot) + if (native.isDirectory) listOf(native) else emptyList()
        if (ownership != null) cleanupOwnedSnapshot(files, directories, ownership)
        else cleanupSnapshot(files, directories)
    }

    private fun contents(): List<File> {
        val children = requireNotNull(directory.listFiles()).toList()
        require(children.size <= 32)
        require(children.all { it.canonicalFile == it.absoluteFile && (it.isFile || it.name == PREPARED_NAME && it.isDirectory) })
        require(children.filter { it.isFile }.all { it.length() in 0..MAX_STORED_BYTES })
        require(children.filter { it.isFile }.sumOf { it.length() } <= MAX_STORED_BYTES + VisionComponentValidator.MAX_APK_BYTES)
        return children
    }

    private fun child(name: String): File = File(directory, name).also { require(it.canonicalFile == it.absoluteFile) }

    private fun <T> locked(checkpoint: () -> Unit, action: () -> T): T {
        checkpoint()
        check(processLock.tryLock()) { "VISION_COMPONENT_BUSY" }
        try {
            require(directory.mkdirs() || directory.isDirectory)
            require(directory.absoluteFile == directory.canonicalFile)
            val lockFile = child(LOCK_NAME)
            require(!lockFile.exists() || lockFile.isFile && lockFile.length() == 0L)
            return RandomAccessFile(lockFile, "rw").use { handle ->
                val lease = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                check(lease != null) { "VISION_COMPONENT_BUSY" }
                lease.use { checkpoint(); action() }
            }
        } finally { processLock.unlock() }
    }

    companion object {
        private val processLock = ReentrantLock()
        private val ID = Regex("[0-9a-f]{64}")
        private val APK_NAME = Regex("[0-9a-f]{64}\\.apk")
        private val SNAPSHOT_NAME = Regex("snapshot-[0-9a-f-]{36}")
        private const val LOCK_NAME = "registry.lock"
        private const val PREPARED_NAME = "prepared"
        private const val COMPLETE_NAME = "complete.marker"
        private const val MAX_APKS = 8
        private const val MAX_STORED_BYTES = 1_073_741_824L
        private const val MAX_PREPARED = 2
        private const val MAX_PREPARED_BYTES = 536_870_912L
        private const val SPACE_RESERVE = 16_777_216L

        fun checkProcessAbi(descriptor: VisionComponentDescriptor, processAbi: String) {
            require(processAbi in setOf("arm64-v8a", "x86_64"))
            if (processAbi !in descriptor.abis) throw VisionComponentDeviceUnsupported()
        }

        private fun snapshotBytes(snapshot: File): Long {
            val children = requireNotNull(snapshot.listFiles()).toList()
            require(children.size <= 6 && children.all { it.canonicalFile == it.absoluteFile &&
                (it.isFile && it.length() in 0..VisionComponentValidator.MAX_APK_BYTES || it.name == "native" && it.isDirectory) })
            return children.sumOf { file ->
                if (file.isFile) file.length() else {
                    val libraries = requireNotNull(file.listFiles()).toList()
                    require(libraries.size <= 2 && libraries.all { it.isFile && it.canonicalFile == it.absoluteFile && it.length() in 0..VisionComponentValidator.MAX_APK_BYTES })
                    libraries.sumOf { it.length() }
                }
            }
        }

        internal fun copyReadOnly(input: InputStream, target: File, maximum: Long, expected: Long?, checkpoint: () -> Unit): Pair<Long, String> {
            require(maximum in 1..VisionComponentValidator.MAX_APK_BYTES && target.canonicalFile == target.absoluteFile && !target.exists())
            require(target.createNewFile())
            // Descriptor is opened first, then permissions sealed before writing
            // any executable bytes (Android34 dynamic-code loading requirement).
            FileOutputStream(target).use { output ->
                require(target.setReadOnly() && !target.canWrite()) { "VISION_COMPONENT_READ_ONLY_FAILED" }
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65_536)
                var size = 0L
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= maximum)
                    require(requireNotNull(target.parentFile).usableSpace >= count + SPACE_RESERVE)
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
                require(size > 0 && (expected == null || size == expected))
                output.flush(); output.fd.sync()
                checkpoint()
                return size to digest.digest().hex()
            }
        }

        internal fun deleteOwnedFile(file: File) {
            require(file.canonicalFile == file.absoluteFile && file.isFile)
            require(file.setWritable(true)) { "VISION_COMPONENT_CLEANUP_FAILED" }
            if (!file.delete()) {
                file.setReadOnly()
                throw IllegalStateException("VISION_COMPONENT_CLEANUP_FAILED")
            }
        }

        internal fun cleanupSnapshot(files: List<File>, directories: List<File>) {
            var failure: Exception? = null
            files.asReversed().forEach { file ->
                try { if (file.exists()) deleteOwnedFile(file) } catch (error: Exception) { if (failure == null) failure = error }
            }
            directories.asReversed().forEach { dir ->
                try {
                    require(dir.canonicalFile == dir.absoluteFile)
                    if (dir.exists()) require(dir.delete()) // Never recursively delete unknown contents.
                } catch (error: Exception) { if (failure == null) failure = error }
            }
            failure?.let { throw it }
        }
    }
}
