package com.basir.inventory.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.basir.inventory.security.KeystoreSecrets
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CategoryEntity::class, SupplierEntity::class, ItemEntity::class, SaleEntity::class,
        MovementEntity::class, LicenseEntity::class, KvEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun items(): ItemDao
    abstract fun sales(): SaleDao
    abstract fun system(): SystemDao
    abstract fun restore(): RestoreDao

    companion object {
        private const val NAME = "ajwa_inventory.db"

        /** Opens the SQLCipher-encrypted database; its passphrase is protected by Android Keystore. */
        fun open(context: Context, secrets: KeystoreSecrets): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(secrets.databasePassphrase())
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addCallback(Seed)
                .build()
        }
    }

    /** Starter data on first launch; the shop can rename or delete any of it. */
    private object Seed : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            listOf("RAK", "RFL", "Nazma", "nPoly").forEach {
                db.execSQL("INSERT INTO suppliers(name) VALUES (?)", arrayOf(it))
            }
            listOf("বাথরুম ফিটিংস", "পাইপ ও ফিটিংস", "স্যানিটারিওয়্যার").forEach {
                db.execSQL("INSERT INTO categories(parentId, name, level) VALUES (NULL, ?, 1)", arrayOf(it))
            }
        }
    }
}
