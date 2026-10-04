package com.example.lifemanager.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.lifemanager.di.IoDispatcher
import com.example.lifemanager.domain.backup.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface BackupStreamAccess {
    fun openRead(location: BackupLocation): InputStream?
    fun openWrite(location: BackupLocation): OutputStream?
}

internal class ContentResolverBackupStreams(private val resolver: ContentResolver) : BackupStreamAccess {
    override fun openRead(location: BackupLocation): InputStream? = resolver.openInputStream(uri(location))
    override fun openWrite(location: BackupLocation): OutputStream? = resolver.openOutputStream(uri(location), "wt")

    private fun uri(location: BackupLocation): Uri = Uri.parse(location.value).also {
        if (it.scheme != "content" || it.authority.isNullOrBlank()) {
            throw BackupFileException("请选择系统文件选择器提供的文档")
        }
    }
}

class AndroidBackupFileStore internal constructor(
    private val streams: BackupStreamAccess,
    private val stagingDirectory: File,
    private val dispatcher: CoroutineDispatcher,
    private val limits: BackupLimits = BackupLimits(),
) : BackupFileStore {
    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher dispatcher: CoroutineDispatcher) :
        this(ContentResolverBackupStreams(context.contentResolver), File(context.cacheDir, "backup-inputs"), dispatcher)

    override suspend fun write(location: BackupLocation, bytes: ByteArray): Unit = withContext(dispatcher) {
        if (bytes.size > limits.maxBytes) throw BackupValidationException("备份超过文件大小限制")
        fileErrors {
            currentCoroutineContext().ensureActive()
            val output = streams.openWrite(location) ?: throw BackupFileException("无法打开输出文件")
            output.use {
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(BUFFER_SIZE, bytes.size - offset)
                    it.write(bytes, offset, count)
                    offset += count
                }
                currentCoroutineContext().ensureActive()
                it.flush()
            }
            currentCoroutineContext().ensureActive()
        }
    }

    override suspend fun read(location: BackupLocation, maxBytes: Int): ByteArray = withContext(dispatcher) {
        checkLimit(maxBytes)
        fileErrors {
            currentCoroutineContext().ensureActive()
            val input = streams.openRead(location) ?: throw BackupFileException("无法打开输入文件")
            input.use { readBounded(it, maxBytes) }.also { currentCoroutineContext().ensureActive() }
        }
    }

    override suspend fun <T> withStagedInput(location: BackupLocation, maxBytes: Int, operation: suspend (ByteArray) -> T): T = withContext(dispatcher) {
        checkLimit(maxBytes)
        var owned: File? = null
        var failure: Throwable? = null
        try {
            val bytes = fileErrors {
                currentCoroutineContext().ensureActive()
                if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) {
                    throw BackupFileException("无法创建私有备份临时区")
                }
                // Never derive a path from the selected URI, and never sweep/delete user files.
                val staged = File.createTempFile("input-", ".tmp", stagingDirectory).also { owned = it }
                val input = streams.openRead(location) ?: throw BackupFileException("无法打开输入文件")
                input.use { source -> FileOutputStream(staged).use { target -> copyBounded(source, target, maxBytes) } }
                FileInputStream(staged).use { readBounded(it, maxBytes) }
            }
            currentCoroutineContext().ensureActive()
            operation(bytes).also { currentCoroutineContext().ensureActive() }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable + dispatcher) {
                owned?.let { file ->
                    try {
                        if (!file.delete() && file.exists()) throw BackupFileException("无法移除私有备份临时文件")
                    } catch (cleanup: Exception) {
                        if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
    }

    private fun checkLimit(maxBytes: Int) { require(maxBytes in 1..limits.maxBytes) }

    private suspend fun readBounded(input: InputStream, maxBytes: Int): ByteArray =
        ByteArrayOutputStream(minOf(BUFFER_SIZE, maxBytes)).use { output ->
            copyBounded(input, output, maxBytes)
            output.toByteArray()
        }

    private suspend fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Int) {
        val buffer = ByteArray(minOf(BUFFER_SIZE, maxBytes + 1))
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            // A single extra byte detects unknown-length overflow without copying it to disk.
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
            currentCoroutineContext().ensureActive()
            if (count < 0) break
            val actual = if (count == 0) {
                val byte = input.read()
                currentCoroutineContext().ensureActive()
                if (byte < 0) break
                buffer[0] = byte.toByte()
                1
            } else count
            if (actual > maxBytes - total) throw BackupValidationException("备份超过文件大小限制")
            output.write(buffer, 0, actual)
            total += actual
        }
        output.flush()
    }

    private suspend fun <T> fileErrors(operation: suspend () -> T): T = try {
        operation()
    } catch (error: IOException) {
        throw BackupFileException("文件读写失败，请检查保存位置、可用空间和文档访问权限", error)
    } catch (error: SecurityException) {
        throw BackupFileException("无法访问文档，请重新选择文件并授予访问权限", error)
    }

    private companion object { const val BUFFER_SIZE = 8192 }
}
