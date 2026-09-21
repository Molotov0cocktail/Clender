package com.molotov.clender.ui.about

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.molotov.clender.diagnostics.DiagnosticMaintenance
import com.molotov.clender.diagnostics.MaintenanceResult
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class MaintenanceResultRegistry(private val context: Context) : ActivityResultRegistry() {
    var failure: RuntimeException? = null
    var intent: Intent? = null
    var launches = 0
    private var request = 0

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        launches += 1
        request = requestCode
        intent = contract.createIntent(context, input)
        failure?.let { throw it }
    }

    fun complete(uri: Uri?) {
        dispatchResult(
            request,
            if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }
        )
    }
}

internal class UiMaintenance : DiagnosticMaintenance {
    val calls = AtomicInteger()
    val logClears = AtomicInteger()
    val cacheClears = AtomicInteger()

    @Volatile
    var result = MaintenanceResult.SUCCESS

    override suspend fun exportTo(output: OutputStream): MaintenanceResult {
        calls.incrementAndGet()
        output.write("synthetic diagnostic codes".toByteArray())
        return result
    }

    override suspend fun clearLogs(): MaintenanceResult {
        calls.incrementAndGet()
        logClears.incrementAndGet()
        return result
    }

    override suspend fun clearTemporaryCache(): MaintenanceResult {
        calls.incrementAndGet()
        cacheClears.incrementAndGet()
        return result
    }
}

internal class SyntheticExportProvider(
    private val file: File,
    private val openedOnMain: AtomicBoolean
) : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
