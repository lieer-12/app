package com.example.lifemanager.data.about

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bundled text only; reading About never requires network or access to business storage. */
object OfflineLicenses {
    suspend fun read(context: Context, asset: String): String = withContext(Dispatchers.IO) {
        require(asset in setOf("third-party-licenses.txt", "apache-2.0.txt"))
        context.applicationContext.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
