package com.example.lifemanager.data.local.dao

import android.database.Cursor
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Restricted by the repository to the fixed, versioned backup schema. */
@Dao
interface BackupSnapshotDao {
    @RawQuery fun read(query: SupportSQLiteQuery): Cursor
}
