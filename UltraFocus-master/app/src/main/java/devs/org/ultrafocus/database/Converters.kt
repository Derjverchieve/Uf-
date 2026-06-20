package devs.org.ultrafocus.database

import androidx.room.TypeConverter
import devs.org.ultrafocus.model.PauseReason
import devs.org.ultrafocus.model.SessionStatus

class Converters {

    @TypeConverter
    fun fromPauseReason(reason: PauseReason): String = reason.name

    @TypeConverter
    fun toPauseReason(value: String): PauseReason =
        runCatching { PauseReason.valueOf(value) }.getOrDefault(PauseReason.MANUAL)

    @TypeConverter
    fun fromSessionStatus(status: SessionStatus): String = status.name

    @TypeConverter
    fun toSessionStatus(value: String): SessionStatus =
        runCatching { SessionStatus.valueOf(value) }.getOrDefault(SessionStatus.CANCELLED)
}
