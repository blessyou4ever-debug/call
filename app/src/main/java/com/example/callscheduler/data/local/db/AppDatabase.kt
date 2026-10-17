package com.example.callscheduler.data.local.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val contactName: String,
    val phoneNumber: String,
    val callTimestampEpochMillis: Long,
    val transcript: String,
    val engineUsed: String
)

@Entity(
    tableName = "action_items",
    foreignKeys = [
        ForeignKey(
            entity = CallRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["callRecordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("callRecordId")]
)
data class ActionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callRecordId: Long,
    val contactName: String,
    val phoneNumber: String,
    val actionType: String,   // APPOINTMENT, TODO, CALLBACK
    val title: String,
    val description: String,
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
    val location: String?,
    val status: String,       // PENDING, CONFIRMED, DISMISSED
    val calendarEventId: Long? = null
)

@Dao
interface CallSchedulerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionItem(item: ActionItemEntity): Long

    @Query("SELECT * FROM action_items ORDER BY id DESC")
    fun observeAllActionItems(): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items WHERE id = :id LIMIT 1")
    suspend fun getActionItemById(id: Long): ActionItemEntity?

    @Update
    suspend fun updateActionItem(item: ActionItemEntity)

    @Query("UPDATE action_items SET status = :status, calendarEventId = :calendarEventId WHERE id = :id")
    suspend fun updateActionStatus(id: Long, status: String, calendarEventId: Long? = null)
}

@Database(
    entities = [CallRecordEntity::class, ActionItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CallSchedulerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "call_scheduler.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
