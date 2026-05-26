package com.example

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_players")
data class SavedPlayer(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String, // Base64 or Emoji
    val seatOrder: Int = 0
)

@Dao
interface SavedPlayerDao {
    @Query("SELECT * FROM saved_players ORDER BY seatOrder ASC")
    fun getAllPlayersFlow(): Flow<List<SavedPlayer>>

    @Query("SELECT * FROM saved_players ORDER BY seatOrder ASC")
    suspend fun getAllPlayers(): List<SavedPlayer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayer(player: SavedPlayer)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(players: List<SavedPlayer>)

    @Query("DELETE FROM saved_players WHERE id = :id")
    suspend fun deletePlayer(id: String)

    @Query("DELETE FROM saved_players")
    suspend fun deleteAllPlayers()
}

@Database(entities = [SavedPlayer::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedPlayerDao(): SavedPlayerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hanyouk_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
