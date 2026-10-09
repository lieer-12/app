package com.example.lifemanager.data.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.example.lifemanager.domain.backup.*
import java.io.File
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ContentResolverBackupStreamsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val location = BackupLocation("content://backup.streams.test/document")

    @Before fun registerProvider() {
        Provider.file = temporary.newFile()
        Provider.modes.clear()
        Provider.denied = false
        val provider = Robolectric.buildContentProvider(Provider::class.java).create("backup.streams.test").get()
        ShadowContentResolver.registerProviderInternal("backup.streams.test", provider)
    }

    @After fun reset() { Provider.file = null; Provider.denied = false; Provider.modes.clear() }

    @Test fun resolverActuallyTruncatesReopensAndStagesContentDocument() = runTest {
        Provider.file!!.writeBytes(ByteArray(100) { 65 })
        val directory = temporary.newFolder()
        val store = AndroidBackupFileStore(ContentResolverBackupStreams(context.contentResolver), directory, UnconfinedTestDispatcher(testScheduler))
        store.write(location, "new".toByteArray())
        assertContentEquals("new".toByteArray(), store.read(location, 16))
        assertEquals("new", store.withStagedInput(location, 16) { it.toString(Charsets.UTF_8) })
        assertEquals(listOf("wt", "r", "r"), Provider.modes)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals("new", Provider.file!!.readText())
    }

    @Test fun nonContentAndMissingAuthorityLocationsAreRejectedWithoutOpeningProvider() {
        val streams = ContentResolverBackupStreams(context.contentResolver)
        for (uri in listOf("file:///private/database", "https://example.com/backup", "content:///no-authority")) {
            assertFailsWith<BackupFileException> { streams.openRead(BackupLocation(uri)) }
            assertFailsWith<BackupFileException> { streams.openWrite(BackupLocation(uri)) }
        }
        assertTrue(Provider.modes.isEmpty())
    }

    @Test fun actualProviderPermissionDenialIsNotSuccess() = runTest {
        Provider.denied = true
        val store = AndroidBackupFileStore(ContentResolverBackupStreams(context.contentResolver), temporary.newFolder(), UnconfinedTestDispatcher(testScheduler))
        assertFailsWith<BackupFileException> { store.read(location, 16) }
        assertFailsWith<BackupFileException> { store.write(location, byteArrayOf(1)) }
        assertTrue(Provider.file!!.exists())
    }

    class Provider : ContentProvider() {
        companion object {
            var file: File? = null
            val modes = mutableListOf<String>()
            var denied = false
        }
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("denied")
            modes += mode
            return ParcelFileDescriptor.open(checkNotNull(file), ParcelFileDescriptor.parseMode(mode))
        }
        override fun getType(uri: Uri) = "application/json"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("not a query")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("not an insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("not a delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("not an update")
    }
}
