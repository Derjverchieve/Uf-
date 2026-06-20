package devs.org.ultrafocus.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import devs.org.ultrafocus.model.PauseEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface PauseEventDao {

    @Insert
    suspend fun insert(event: PauseEvent): Long

    @Update
    suspend fun update(event: PauseEvent)

    @Query("SELECT * FROM PauseEvent WHERE sessionId = :sessionId ORDER BY startTime ASC")
    suspend fun getForSessionSync(sessionId: Long): List<PauseEvent>

    @Query("SELECT * FROM PauseEvent WHERE sessionId = :sessionId ORDER BY startTime ASC")
    fun getForSession(sessionId: Long): Flow<List<PauseEvent>>

    @Query("SELECT * FROM PauseEvent WHERE sessionId = :sessionId AND endTime IS NULL LIMIT 1")
    suspend fun getOpenPauseForSession(sessionId: Long): PauseEvent?
}
