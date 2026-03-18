package com.kibot.android.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kibot.shared.models.BotEffectiveState
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_bot_state")
data class CachedBotStateEntity(
    @PrimaryKey val botId: String,
    val desiredOn: Boolean,
    val effectiveState: String,
    val activeEngine: String,
    val standbyEngine: String,
    val syncHealth: String,
    val pnlTodayIdr: String,
    val drawdownPct: Double,
    val lastHeartbeatEpochMs: Long,
)

@Entity(tableName = "cached_logs")
data class CachedLogEntity(
    @PrimaryKey val logId: String,
    val level: String,
    val category: String,
    val message: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "cached_trades")
data class CachedTradeEntity(
    @PrimaryKey val tradeId: String,
    val pairId: String,
    val side: String,
    val realizedPnlIdr: String,
    val createdAtEpochMs: Long,
)

@Dao
interface AppDao {
    @Query("select * from cached_bot_state where botId = :botId")
    fun observeBotState(botId: String = "main"): Flow<CachedBotStateEntity?>

    @Query("select * from cached_logs order by createdAtEpochMs desc limit 50")
    fun observeLogs(): Flow<List<CachedLogEntity>>

    @Query("select * from cached_trades order by createdAtEpochMs desc limit 50")
    fun observeTrades(): Flow<List<CachedTradeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBotState(state: CachedBotStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLogs(entries: List<CachedLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrades(entries: List<CachedTradeEntity>)
}

@Database(
    entities = [
        CachedBotStateEntity::class,
        CachedLogEntity::class,
        CachedTradeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "kibot.db",
            ).build()
        }
    }
}
