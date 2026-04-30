package devs.org.ultrafocus.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(private val repository: AppRepository) : ViewModel() {

    // UI State
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    // 1. LIST ALL APPS (With Self-Protection)
    fun listAllApps(context: Context): List<AppInfo> {
        val allApps = repository.getInstalledApps(context)
        // Filter out the UltraFocus app itself so you don't lock yourself out
        return allApps.filter { it.packageName != context.packageName }
    }

    // 2. GET BLOCKED APPS
    suspend fun getBlockedApps(): MutableList<AppInfo>{
        val list = mutableListOf<AppInfo>()
        list.addAll(repository.getBlockedApps())
        return list
    }

    // 3. BLOCK APP (Renamed from insertApp to match your Activity)
    suspend fun blockApp(blockedApp: AppInfo) {
        try {
            repository.addBlockedApp(blockedApp)
        } catch (e: Exception) {
            _error.value = "Failed to block app: ${e.message}"
        }
    }

    // 4. UNBLOCK APP (Renamed from deleteApp to match your Activity)
    suspend fun removeBlockedApp(blockedApp: AppInfo) {
        try {
            repository.removeBlockedApp(blockedApp)
        } catch (e: Exception) {
            _error.value = "Failed to remove app: ${e.message}"
        }
    }

    // 5. UPDATE TIME (Global)
    suspend fun updateBlockedAppsTimePeriod(fromTime: String?, toTime: String?, repeatMode: String?) {
        val blockedApps = getBlockedApps()
        blockedApps.forEach { app ->
            // We use 'fromTime' to store the schedule string (e.g., "09:00-12:00,14:00-16:00")
            val updatedApp = app.copy(fromTime = fromTime, toTime = toTime, repeatMode = repeatMode)
            repository.addBlockedApp(updatedApp)
        }
    }

    // 6. UPDATE TIME (Single App)
    suspend fun updateSingleAppTimePeriod(appInfo: AppInfo, fromTime: String?, toTime: String?, repeatMode: String?) {
        val updatedApp = appInfo.copy(fromTime = fromTime, toTime = toTime, repeatMode = repeatMode)
        repository.addBlockedApp(updatedApp)
    }

    // Helper to clear errors
    fun clearError() {
        _error.value = null
    }
}