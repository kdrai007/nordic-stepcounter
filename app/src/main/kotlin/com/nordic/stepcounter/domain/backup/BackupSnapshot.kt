package com.nordic.stepcounter.domain.backup

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

data class BackupProfile(
    val heightCm: Float,
    val weightKg: Float,
    val isMale: Boolean,
    val customStrideLengthMeters: Float,
    val dailyGoal: Int,
    val appTheme: AppTheme
) {
    fun toUserProfile(): UserProfile = UserProfile(
        heightCm = heightCm.takeIf { it.isFinite() }?.coerceIn(100f, 250f) ?: 175.0f,
        weightKg = weightKg.takeIf { it.isFinite() }?.coerceIn(30f, 250f) ?: 70.0f,
        isMale = isMale,
        customStrideLengthMeters = customStrideLengthMeters
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(0.30f, 1.50f) ?: 0.0f,
        dailyGoal = dailyGoal.coerceIn(1_000, 100_000),
        appTheme = appTheme
    )

    companion object {
        fun fromUserProfile(profile: UserProfile): BackupProfile = BackupProfile(
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            isMale = profile.isMale,
            customStrideLengthMeters = profile.customStrideLengthMeters,
            dailyGoal = profile.dailyGoal,
            appTheme = profile.appTheme
        )
    }
}

data class BackupDailyStep(
    val date: String,
    val steps: Int,
    val distanceMeters: Float,
    val caloriesKcal: Float,
    val activeDurationSeconds: Long,
    val goalSteps: Int
) {
    companion object {
        fun fromEntity(entity: DailyStepEntity): BackupDailyStep = BackupDailyStep(
            date = entity.date,
            steps = entity.steps,
            distanceMeters = entity.distanceMeters,
            caloriesKcal = entity.caloriesKcal,
            activeDurationSeconds = entity.activeDurationSeconds,
            goalSteps = entity.goalSteps
        )
    }
}

data class BackupHourlyStep(
    val date: String,
    val hour: Int,
    val steps: Int
) {
    companion object {
        fun fromEntity(entity: HourlyStepEntity): BackupHourlyStep = BackupHourlyStep(
            date = entity.date,
            hour = entity.hour,
            steps = entity.steps
        )
    }
}

data class BackupSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val appVersionName: String,
    val profile: BackupProfile,
    val dailySteps: List<BackupDailyStep>,
    val hourlySteps: List<BackupHourlyStep>
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class BackupSummary(
    val exportedAtEpochMs: Long,
    val dayCount: Int,
    val totalSteps: Long,
    val earliestDate: String?,
    val latestDate: String?,
    val isEncrypted: Boolean
)

data class RestoreStats(
    val mergedDaysCount: Int,
    val updatedDaysCount: Int,
    val updatedHourlyCount: Int,
    val profileRestored: Boolean
)

object BackupSerializer {
    fun toJson(snapshot: BackupSnapshot): JSONObject {
        val profileJson = JSONObject().apply {
            put("heightCm", finiteOrZero(snapshot.profile.heightCm))
            put("weightKg", finiteOrZero(snapshot.profile.weightKg))
            put("isMale", snapshot.profile.isMale)
            put("customStrideLengthMeters", finiteOrZero(snapshot.profile.customStrideLengthMeters))
            put("dailyGoal", snapshot.profile.dailyGoal)
            put("appTheme", snapshot.profile.appTheme.storageKey)
        }

        val dailyArray = JSONArray()
        snapshot.dailySteps.forEach { daily ->
            dailyArray.put(
                JSONObject().apply {
                    put("date", daily.date)
                    put("steps", daily.steps)
                    put("distanceMeters", finiteOrZero(daily.distanceMeters))
                    put("caloriesKcal", finiteOrZero(daily.caloriesKcal))
                    put("activeDurationSeconds", daily.activeDurationSeconds)
                    put("goalSteps", daily.goalSteps)
                }
            )
        }

        val hourlyArray = JSONArray()
        snapshot.hourlySteps.forEach { hourly ->
            hourlyArray.put(
                JSONObject().apply {
                    put("date", hourly.date)
                    put("hour", hourly.hour)
                    put("steps", hourly.steps)
                }
            )
        }

        return JSONObject().apply {
            put("schemaVersion", snapshot.schemaVersion)
            put("exportedAtEpochMs", snapshot.exportedAtEpochMs)
            put("appVersionName", snapshot.appVersionName)
            put("profile", profileJson)
            put("dailySteps", dailyArray)
            put("hourlySteps", hourlyArray)
        }
    }

    fun fromJson(json: JSONObject): BackupSnapshot {
        val schemaVersion = json.optInt("schemaVersion", -1)
        if (schemaVersion < 1) {
            throw JSONException("Missing or invalid schemaVersion: $schemaVersion")
        }
        if (schemaVersion > BackupSnapshot.CURRENT_SCHEMA_VERSION) {
            throw UnsupportedBackupVersionException(schemaVersion)
        }

        val exportedAt = json.optLong("exportedAtEpochMs", 0L).coerceAtLeast(0L)
        val appVersionName = json.optString("appVersionName", "")

        val profileObj = json.optJSONObject("profile")
            ?: throw JSONException("Missing profile object")
        val profile = BackupProfile(
            heightCm = profileObj.optDouble("heightCm", 175.0).toFloat(),
            weightKg = profileObj.optDouble("weightKg", 70.0).toFloat(),
            isMale = profileObj.optBoolean("isMale", true),
            customStrideLengthMeters = profileObj.optDouble("customStrideLengthMeters", 0.0).toFloat(),
            dailyGoal = profileObj.optInt("dailyGoal", 6000),
            appTheme = AppTheme.fromStorageValue(profileObj.optString("appTheme", ""))
        )

        val dailyArray = json.optJSONArray("dailySteps") ?: JSONArray()
        val dailySteps = buildList(dailyArray.length()) {
            for (i in 0 until dailyArray.length()) {
                val obj = dailyArray.optJSONObject(i) ?: continue
                val date = obj.optString("date", "")
                if (!isValidIsoDate(date)) continue
                add(
                    BackupDailyStep(
                        date = date,
                        steps = obj.optInt("steps", 0).coerceAtLeast(0),
                        distanceMeters = obj.optDouble("distanceMeters", 0.0).toFloat()
                            .takeIf { it.isFinite() && it >= 0f } ?: 0f,
                        caloriesKcal = obj.optDouble("caloriesKcal", 0.0).toFloat()
                            .takeIf { it.isFinite() && it >= 0f } ?: 0f,
                        activeDurationSeconds = obj.optLong("activeDurationSeconds", 0L).coerceAtLeast(0L),
                        goalSteps = obj.optInt("goalSteps", profile.dailyGoal)
                    )
                )
            }
        }

        val hourlyArray = json.optJSONArray("hourlySteps") ?: JSONArray()
        val hourlySteps = buildList(hourlyArray.length()) {
            for (i in 0 until hourlyArray.length()) {
                val obj = hourlyArray.optJSONObject(i) ?: continue
                val date = obj.optString("date", "")
                val hour = obj.optInt("hour", -1)
                if (!isValidIsoDate(date) || hour !in 0..23) continue
                add(
                    BackupHourlyStep(
                        date = date,
                        hour = hour,
                        steps = obj.optInt("steps", 0).coerceAtLeast(0)
                    )
                )
            }
        }

        return BackupSnapshot(
            schemaVersion = schemaVersion,
            exportedAtEpochMs = exportedAt,
            appVersionName = appVersionName,
            profile = profile,
            dailySteps = dailySteps,
            hourlySteps = hourlySteps
        )
    }

    fun summarize(snapshot: BackupSnapshot, isEncrypted: Boolean): BackupSummary {
        val hourlySumsByDate = snapshot.hourlySteps
            .groupBy { it.date }
            .mapValues { (_, entries) -> entries.sumOf { it.steps.toLong() } }
        val dailyByDate = snapshot.dailySteps.associateBy { it.date }
        val allDates = (dailyByDate.keys + hourlySumsByDate.keys).sorted()

        val totalSteps = allDates.sumOf { date ->
            maxOf(
                dailyByDate[date]?.steps?.toLong() ?: 0L,
                hourlySumsByDate[date] ?: 0L
            )
        }

        return BackupSummary(
            exportedAtEpochMs = snapshot.exportedAtEpochMs,
            dayCount = allDates.size,
            totalSteps = totalSteps,
            earliestDate = allDates.firstOrNull(),
            latestDate = allDates.lastOrNull(),
            isEncrypted = isEncrypted
        )
    }

    internal fun isValidIsoDate(raw: String): Boolean {
        if (raw.length != 10) return false
        return runCatching { LocalDate.parse(raw) }.isSuccess
    }

    private fun finiteOrZero(value: Float): Double =
        if (value.isFinite()) value.toDouble() else 0.0
}

class UnsupportedBackupVersionException(val version: Int) :
    IllegalArgumentException("Unsupported backup version: $version")
