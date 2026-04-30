package devs.org.ultrafocus.model

import android.graphics.drawable.Drawable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "AppInfo")
data class AppInfo(
    @PrimaryKey(autoGenerate = false) val packageName: String,
    val appName: String,
    val icon: Drawable,
    val blockedAt: Long = System.currentTimeMillis(),
    val isBlocked : Boolean = false,

    // CHANGED: This field will now hold comma-separated ranges
    // Format: "09:00-12:00,15:00-18:00"
    val fromTime: String? = null,

    // We will ignore this field for logic, but keep it to prevent DB crash
    val toTime: String? = null,
    val repeatMode: String? = null
)