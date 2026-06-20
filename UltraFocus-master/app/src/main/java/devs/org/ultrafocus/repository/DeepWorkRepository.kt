package devs.org.ultrafocus.repository

import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.PauseEvent
import kotlinx.coroutines.flow.Flow

class DeepWorkRepository(private val db: AppDatabase) {

    private val sessionDao = db.focusSessionDao()
    private val pauseDao = db.pauseEventDao()

    // ── Sessions ─────────────────────────────────────────────────────────
    suspend fun insertSession(session: FocusSession): Long = sessionDao.insert(session)
    suspend fun updateSession(session: FocusSession) = sessionDao.update(session)
    suspend fun getSession(id: Long): FocusSession? = sessionDao.getById(id)
    suspend fun getRunningSession(): FocusSession? = sessionDao.getRunningSession()
    fun observeAllSessions(): Flow<List<FocusSession>> = sessionDao.getAllSessions()
    suspend fun getCompletedSessionsBetween(start: Long, end: Long): List<FocusSession> =
        sessionDao.getCompletedBetween(start, end)

    // ── Pause events ─────────────────────────────────────────────────────
    suspend fun insertPauseEvent(event: PauseEvent): Long = pauseDao.insert(event)
    suspend fun updatePauseEvent(event: PauseEvent) = pauseDao.update(event)
    suspend fun getPauseEventsForSession(sessionId: Long): List<PauseEvent> =
        pauseDao.getForSessionSync(sessionId)
    fun observePauseEventsForSession(sessionId: Long): Flow<List<PauseEvent>> =
        pauseDao.getForSession(sessionId)
    suspend fun getOpenPauseEvent(sessionId: Long): PauseEvent? =
        pauseDao.getOpenPauseForSession(sessionId)
}
