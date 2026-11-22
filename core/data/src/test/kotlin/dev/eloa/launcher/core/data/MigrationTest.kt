package dev.eloa.launcher.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.eloa.launcher.core.data.db.LauncherDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A database created by launcher 1.0 (schema 1) opens with version 2 and keeps its rows. */
    @Test fun `v1 database migrates to v2 without data loss`() {
        val name = "migrate.db"
        ctx.deleteDatabase(name)
        // Create schema 1 exactly as exported by Room for launcher 1.0.
        val schema = org.json.JSONObject(java.io.File("schemas/dev.eloa.launcher.core.data.db.LauncherDatabase/1.json").readText()).getJSONObject("database")
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val e = entities.getJSONObject(i)
                        db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                        val idx = e.optJSONArray("indices")
                        if (idx != null) for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO java_profiles (id, name, description, iconKey, coverKey, minecraftVersion, loader, gameDirName, minMemoryMb, maxMemoryMb, jvmArgs, gameArgs, graphicsPreset, createdAt, installState, pendingProcessors) " +
                "VALUES ('p1', 'Old', '', 'grass', 'forest', '1.20.1', 'FABRIC', 'old', 512, 2048, '', '', 'HIGH', 1, 'INSTALLED', 0)",
        )
        helper.close()

        val db = Room.databaseBuilder(ctx, LauncherDatabase::class.java, name).addMigrations(LauncherDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        runBlocking {
            val p = db.profileDao().byId("p1")!!
            assertEquals("Old", p.name); assertEquals("AUTO", p.renderer); assertNull(p.runtimeName); assertEquals(1f, p.resolutionScale, 0f)
            assertEquals(0, db.worldBackupDao().observeAll().let { it.first() }.size)
            db.notificationDao().insert(dev.eloa.launcher.core.data.db.NotificationEntity(type = "INFO", title = "t", message = "m", createdAt = 1))
            assertEquals(1, db.notificationDao().observeUnread().first())
        }
        db.close()
        ctx.deleteDatabase(name)
    }
}
