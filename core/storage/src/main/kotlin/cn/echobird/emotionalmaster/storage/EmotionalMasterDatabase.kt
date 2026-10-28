package cn.echobird.emotionalmaster.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        PersonEntity::class,
        ImportBatchEntity::class,
        MessageEntity::class,
        MessageAttachmentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class EmotionalMasterDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun openEncrypted(
            context: Context,
            databaseName: String,
            keyProvider: DatabaseKeyProvider,
        ): EmotionalMasterDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(keyProvider.getOrCreatePassphrase())
            return Room.databaseBuilder(
                context,
                EmotionalMasterDatabase::class.java,
                databaseName,
            ).openHelperFactory(factory).build()
        }
    }
}
