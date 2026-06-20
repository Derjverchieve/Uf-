package devs.org.ultrafocus

import android.app.Application
import com.google.android.material.color.DynamicColors
import devs.org.ultrafocus.utils.DeepWorkSessionManager

class UltraFocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        DeepWorkSessionManager.init(this)
    }
}