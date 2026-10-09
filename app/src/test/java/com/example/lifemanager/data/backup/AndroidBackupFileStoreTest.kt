package com.example.lifemanager.data.backup

import com.example.lifemanager.domain.backup.*
import java.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidBackupFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val location = BackupLocation("content://backup.test/document")

    @Test fun initializationOnlyRemovesOwnedOrphansNotOtherFilesOrDirectories() = runTest {
        val directory = temporary.newFolder()
        val orphan = File.createTempFile("input-", ".tmp", directory).apply { writeText("synthetic previous input") }
        val unrelated = File(directory, "keep.json").apply { writeText("synthetic unrelated file") }
        val similar = File(directory, "input-keep.tmp").apply { writeText("not an owned input") }
        val nested = File(directory, "input-123.tmp").apply { mkdir(); File(this, "keep").writeText("no recursive deletion") }
        store(Streams(byteArrayOf()), UnconfinedTestDispatcher(testScheduler), directory).initializePrivateStorage()
        assertFalse(orphan.exists())
        assertTrue(unrelated.exists())
        assertTrue(similar.exists())
        assertTrue(File(nested, "keep").exists())
    }

    @Test fun secondStoreInitializationCannotDeleteFirstStoresActiveStagedInput() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val first = store(Streams(byteArrayOf(1)), dispatcher, directory)
        first.withStagedInput(location, 16) {
            val activeFile = directory.listFiles()!!.single()
            store(Streams(byteArrayOf()), dispatcher, directory).initializePrivateStorage()
            assertTrue(activeFile.exists())
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun boundedReadAcceptsExactLimitWithoutTrustingAvailableAndClosesInput() = runTest {
        val access = Streams(ByteArray(16) { it.toByte() })
        val store = store(access, UnconfinedTestDispatcher(testScheduler))
        assertContentEquals(ByteArray(16) { it.toByte() }, store.read(location, 16))
        assertTrue(access.input.closed)
    }

    @Test fun unknownLengthOversizeIsRejectedAndInputClosed() = runTest {
        val access = Streams(ByteArray(17))
        assertFailsWith<BackupValidationException> { store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
        assertTrue(access.input.closed)
        assertTrue(access.input.bytesRead <= 17)
    }

    @Test fun oneByteProbeRejectsLargeSourceWithoutConsumingWholeStream() = runTest {
        val access = Streams(ByteArray(100_000))
        assertFailsWith<BackupValidationException> { store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
        assertEquals(17, access.input.bytesRead)
    }

    @Test fun zeroLengthAndZeroProgressReadAreHandled() = runTest {
        val empty = Streams(byteArrayOf())
        assertContentEquals(byteArrayOf(), store(empty, UnconfinedTestDispatcher(testScheduler)).read(location, 16))
        val access = Streams(byteArrayOf(1, 2, 3))
        access.input.zeroOnce = true
        assertContentEquals(byteArrayOf(1, 2, 3), store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16))
        assertTrue(access.input.closed)
    }

    @Test fun invalidLimitsAreRejectedBeforeOpeningSource() = runTest {
        val access = Streams(byteArrayOf())
        val store = store(access, UnconfinedTestDispatcher(testScheduler))
        assertFailsWith<IllegalArgumentException> { store.read(location, 0) }
        assertFailsWith<IllegalArgumentException> { store.read(location, 17) }
        assertEquals(0, access.readOpens)
    }

    @Test fun missingReadStreamFailsExplicitly() = runTest {
        val access = Streams(byteArrayOf())
        access.nullRead = true
        assertFailsWith<BackupFileException> { store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
    }

    @Test fun permissionFailureIsAnActionableFileErrorNotProviderPath() = runTest {
        val access = Streams(byteArrayOf())
        access.openReadFailure = SecurityException("private-provider-secret")
        val error = assertFailsWith<BackupFileException> { store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
        assertFalse(error.message.orEmpty().contains("private-provider-secret"))
    }

    @Test fun readAndCloseFailuresAreNotIgnored() = runTest {
        val access = Streams(byteArrayOf(1))
        access.input.readFailure = IOException("read failed")
        assertFailsWith<BackupFileException> { store(access, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
        assertTrue(access.input.closed)
        val other = Streams(byteArrayOf(1))
        other.input.closeFailure = true
        assertFailsWith<BackupFileException> { store(other, UnconfinedTestDispatcher(testScheduler)).read(location, 16) }
    }

    @Test fun outputIsFlushedAndClosedBeforeWriteReturns() = runTest {
        val access = Streams(byteArrayOf())
        store(access, UnconfinedTestDispatcher(testScheduler)).write(location, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), access.output.toByteArray())
        assertTrue(access.output.flushed)
        assertTrue(access.output.closed)
    }

    @Test fun outputOverLimitIsRejectedBeforeOpeningFile() = runTest {
        val access = Streams(byteArrayOf())
        assertFailsWith<BackupValidationException> { store(access, UnconfinedTestDispatcher(testScheduler)).write(location, ByteArray(17)) }
        assertEquals(0, access.writeOpens)
    }

    @Test fun missingWriteStreamFailsExplicitly() = runTest {
        val access = Streams(byteArrayOf())
        access.nullWrite = true
        assertFailsWith<BackupFileException> { store(access, UnconfinedTestDispatcher(testScheduler)).write(location, byteArrayOf(1)) }
    }

    @Test fun writeFlushAndCloseFailuresNeverReturnSuccess() = runTest {
        for (failure in listOf("write", "flush", "close")) {
            val access = Streams(byteArrayOf())
            access.output.failure = failure
            assertFailsWith<BackupFileException>(failure) { store(access, UnconfinedTestDispatcher(testScheduler)).write(location, byteArrayOf(1)) }
            assertTrue(access.output.closed, failure)
        }
    }

    @Test fun stagedInputIsCompletePrivateAndRemovedAfterSuccessWithoutDeletingUnrelatedFiles() = runTest {
        val directory = temporary.newFolder()
        val unrelated = File(directory, "unrelated.keep").apply { writeText("keep") }
        val access = Streams(byteArrayOf(1, 2, 3))
        val store = store(access, UnconfinedTestDispatcher(testScheduler), directory)
        val result = store.withStagedInput(location, 16) { bytes ->
            assertTrue(access.input.closed)
            val staged = directory.listFiles()!!.single { it != unrelated }
            assertTrue(staged.name.startsWith("input-"))
            assertContentEquals(bytes, staged.readBytes())
            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            "preview"
        }
        assertEquals("preview", result)
        assertEquals(listOf(unrelated), directory.listFiles()!!.toList())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun stageValidationFailureCleansOnlyOwnedFileAndPropagatesOriginalError() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1))
        val error = BackupValidationException("invalid format")
        assertSame(error, assertFailsWith<BackupValidationException> {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) { throw error }
        })
        assertTrue(access.input.closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun oversizeStageIsRejectedBeforeValidationAndPartialPrivateCopyRemoved() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(ByteArray(1000))
        var validated = false
        assertFailsWith<BackupValidationException> {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) { validated = true }
        }
        assertFalse(validated)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(access.input.closed)
    }

    @Test fun copyIOFailureRemovesPartialPrivateFile() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1))
        access.input.readFailure = IOException("broken source")
        assertFailsWith<BackupFileException> {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) { error("must not validate") }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(access.input.closed)
    }

    @Test fun invalidStagingDirectoryFailsBeforeOpeningSource() = runTest {
        val blocked = temporary.newFile()
        val access = Streams(byteArrayOf(1))
        assertFailsWith<BackupFileException> {
            store(access, UnconfinedTestDispatcher(testScheduler), blocked).withStagedInput(location, 16) { error("must not validate") }
        }
        assertEquals(0, access.readOpens)
        assertTrue(blocked.isFile)
    }

    @Test fun cancellationDuringReadClosesInputAndRemovesStage() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1, 2, 3))
        var validated = false
        val task = async {
            val job = currentCoroutineContext().job
            access.input.onRead = { job.cancel() }
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) { validated = true }
        }
        assertFailsWith<CancellationException> { task.await() }
        assertFalse(validated)
        assertTrue(access.input.closed)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringPreviewCleansPrivateStage() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1))
        val entered = CompletableDeferred<Unit>()
        val task = async {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) {
                entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await()
        task.cancelAndJoin()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(access.input.closed)
    }

    @Test fun cancellationDuringWriteClosesOutputWithoutSuccess() = runTest {
        val access = Streams(byteArrayOf())
        var success = false
        val task = async {
            val job = currentCoroutineContext().job
            access.output.onWrite = { job.cancel() }
            store(access, UnconfinedTestDispatcher(testScheduler)).write(location, byteArrayOf(1))
            success = true
        }
        assertFailsWith<CancellationException> { task.await() }
        assertFalse(success)
        assertTrue(access.output.closed)
    }

    @Test fun directoryCreatedByAnotherPreviewBetweenCheckAndMkdirsIsAccepted() = runTest {
        val directory = object : File(temporary.root, "concurrent-inputs") {
            override fun mkdirs(): Boolean {
                assertTrue(super.mkdirs()) // Simulate another operation having just created it.
                return false
            }
        }
        val access = Streams(byteArrayOf(1, 2))
        val result = store(access, UnconfinedTestDispatcher(testScheduler), directory)
            .withStagedInput(location, 16) { it.toList() }
        assertEquals(listOf<Byte>(1, 2), result)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(access.input.closed)
    }

    @Test fun cleanupFailureDoesNotTurnSuccessfulValidationIntoSuccessfulPreview() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1))
        assertFailsWith<BackupFileException> {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) {
                replaceOwnedFileWithNonEmptyDirectory(directory)
                "validated"
            }
        }
        assertEquals("fault-fixture", File(directory.listFiles()!!.single(), "keep").readText())
        assertTrue(access.input.closed)
    }

    @Test fun cleanupFailureIsSuppressedWithoutMaskingOriginalValidationFailure() = runTest {
        val directory = temporary.newFolder()
        val access = Streams(byteArrayOf(1))
        val original = BackupValidationException("validation failure")
        val error = assertFailsWith<BackupValidationException> {
            store(access, UnconfinedTestDispatcher(testScheduler), directory).withStagedInput(location, 16) {
                replaceOwnedFileWithNonEmptyDirectory(directory)
                throw original
            }
        }
        assertSame(original, error)
        assertIs<BackupFileException>(error.suppressed.single())
        assertEquals("fault-fixture", File(directory.listFiles()!!.single(), "keep").readText())
        assertTrue(access.input.closed)
    }

    private fun replaceOwnedFileWithNonEmptyDirectory(directory: File) {
        // Deterministic real filesystem delete failure, without depending on host ACL semantics.
        val owned = directory.listFiles()!!.single()
        assertTrue(owned.delete())
        assertTrue(owned.mkdir())
        File(owned, "keep").writeText("fault-fixture")
    }

    private fun store(access: Streams, dispatcher: CoroutineDispatcher, directory: File = temporary.newFolder()) =
        AndroidBackupFileStore(access, directory, dispatcher, BackupLimits(maxBytes = 16))

    private class Streams(bytes: ByteArray) : BackupStreamAccess {
        val input = Input(bytes)
        val output = Output()
        var nullRead = false
        var nullWrite = false
        var openReadFailure: Exception? = null
        var readOpens = 0
        var writeOpens = 0
        override fun openRead(location: BackupLocation): InputStream? {
            readOpens++; openReadFailure?.let { throw it }; return if (nullRead) null else input
        }
        override fun openWrite(location: BackupLocation): OutputStream? {
            writeOpens++; return if (nullWrite) null else output
        }
    }

    private class Input(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var closeFailure = false
        var readFailure: IOException? = null
        var onRead: (() -> Unit)? = null
        var bytesRead = 0
        var zeroOnce = false
        override fun available(): Int = error("must not trust available")
        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            readFailure?.let { throw it }
            if (zeroOnce) { zeroOnce = false; return 0 }
            val count = super.read(bytes, off, len)
            if (count > 0) bytesRead += count
            onRead?.invoke()
            return count
        }
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
        override fun close() { closed = true; if (closeFailure) throw IOException("close failed") }
    }

    private class Output : ByteArrayOutputStream() {
        var closed = false
        var flushed = false
        var failure: String? = null
        var onWrite: (() -> Unit)? = null
        override fun write(bytes: ByteArray, off: Int, len: Int) {
            if (failure == "write") throw IOException("write failed")
            super.write(bytes, off, len); onWrite?.invoke()
        }
        override fun flush() { flushed = true; if (failure == "flush") throw IOException("flush failed") }
        override fun close() { closed = true; if (failure == "close") throw IOException("close failed") }
    }
}
