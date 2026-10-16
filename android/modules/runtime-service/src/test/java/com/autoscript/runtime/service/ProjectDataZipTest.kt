package com.autoscript.runtime.service

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectDataZipTest {
    @get:Rule val temporary=TemporaryFolder()
    private val ops=object : ProjectDataFileOps {
        override fun kind(file: File): ProjectDataKind? = when {
            !Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS) -> null
            Files.isSymbolicLink(file.toPath()) -> ProjectDataKind.UNSAFE
            file.isDirectory -> ProjectDataKind.DIRECTORY
            file.isFile -> ProjectDataKind.FILE
            else -> ProjectDataKind.UNSAFE
        }
        override fun input(file: File): InputStream=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)
        override fun outputExclusive(file: File): OutputStream=Files.newOutputStream(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
        override fun moveAtomic(source: File,target: File) { Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE) }
    }
    @Test fun archiveRoundTripIncludesEmptyDirectoryAndNeverMergesExistingDestination() {
        val root=File(temporary.root.canonicalFile,"project")
        val store=ProjectDataStore(root,ops)
        val commit: (() -> Unit) -> Unit={it()}
        store.write("source/file.txt","汉字".toByteArray(),{},commit)
        store.mkdir("source/empty",{},commit)
        store.zip("source","bundle.zip",{},commit)
        store.unzip("bundle.zip","restored",{},commit)
        assertEquals("汉字",store.read("restored/file.txt",{}).toString(Charsets.UTF_8))
        assertTrue(File(root,"data/restored/empty").isDirectory)
        assertThrows(ProjectDataFailure::class.java) { store.unzip("bundle.zip","restored",{},commit) }
        assertEquals("汉字",store.read("restored/file.txt",{}).toString(Charsets.UTF_8))
    }
    @Test fun invalidNamesAndCorruptionCannotPublishPartialDirectory() {
        val root=File(temporary.root.canonicalFile,"invalid")
        val store=ProjectDataStore(root,ops)
        for((index,name) in listOf("../escaped", "/absolute", "a/../../escaped", "C:/escaped").withIndex()) {
            val output=ByteArrayOutputStream()
            ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write("content".toByteArray()); zip.closeEntry() }
            File(root,"data/bad$index.zip").writeBytes(output.toByteArray())
            assertThrows(Exception::class.java) { store.unzip("bad$index.zip","partial$index",{}) {it()} }
            assertFalse(File(root,"data/partial$index").exists())
            assertTrue(File(root,".archive-stage").listFiles()!!.isEmpty())
        }
        File(root,"data/broken.zip").writeBytes(byteArrayOf(80,75,3,4))
        assertThrows(Exception::class.java) { store.unzip("broken.zip","partial",{}) {it()} }
        assertFalse(File(root,"data/partial").exists())
    }
    @Test fun cancellingBeforePublishPreservesExistingDataAndCleansOnlyOwnStaging() {
        val root=File(temporary.root.canonicalFile,"cancel")
        val store=ProjectDataStore(root,ops)
        store.write("source/file","kept".toByteArray(),{},{it()})
        store.zip("source","bundle.zip",{},{it()})
        assertThrows(ProjectDataFailure::class.java) { store.unzip("bundle.zip","destination",{}) {throw ProjectDataFailure("FILE_CANCELLED")} }
        assertFalse(File(root,"data/destination").exists())
        assertEquals("kept",store.read("source/file",{}).toString(Charsets.UTF_8))
        assertTrue(File(root,".archive-stage").listFiles()!!.isEmpty())
    }
}
