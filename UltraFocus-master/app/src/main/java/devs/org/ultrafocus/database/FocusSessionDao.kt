package devs.org.ultrafocus.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import devs.org.ultrafocus.model.FocusSession
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {

    @Insert
    suspend fun insert(session: FocusSession): Long

    @Update
    suspend fun update(session: FocusSession)

    @Query("SELECT * FROM FocusSession WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FocusSession?

    @Query("SELECT * FROM FocusSession ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<FocusSession>>

    // For crash/process-death recovery — see DeepWorkSessionManager.recoverOrphanedSession().
    @Query("SELECT * FROM FocusSession WHERE status = 'RUNNING' LIMIT 1")
    suspend fun getRunningSession(): FocusSession?

    @Query("SELECT * FROM FocusSession WHERE status = 'COMPLETED' AND startTime BETWEEN :start AND :end ORDER BY startTime DESC")
    suspend fun getCompletedBetween(start: Long, end: Long): List<FocusSession>

    // All completed sessions ordered oldest-first, for monthly aggregation.
    @Query("SELECT * FROM FocusSession WHERE status = 'COMPLETED' ORDER BY startTime ASC")
    suspend fun getAllCompleted(): List<FocusSession>

    @Query("DELETE FROM FocusSession WHERE id = :id")
    suspend fun deleteById(id: Long)
}
