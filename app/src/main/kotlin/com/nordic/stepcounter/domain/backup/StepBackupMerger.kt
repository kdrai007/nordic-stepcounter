package com.nordic.stepcounter.domain.backup

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.domain.StepCalculationEngine

data class MergedBackupResult(
    val dailyUpserts: List<DailyStepEntity>,
    val hourlyUpserts: List<HourlyStepEntity>,
    val mergedDaysCount: Int,
    val updatedDaysCount: Int,
    val updatedHourlyCount: Int
)

/**
 * Pure domain merger for restoring step history without losing local progress
 * or creating daily/hourly discrepancies.
 *
 * Merge order:
 * 1. Merge `hourly_steps` first via `max(local, backup)` per `(date, hour)`.
 * 2. Derive each date's final daily step count as `max(local.steps, backup.steps, sum(mergedHourly))`.
 * 3. Recalculate distance, calories, and active duration whenever the merged hourly sum
 *    exceeds both `local.steps` and `backup.steps` so Today and History stay consistent.
 */
object StepBackupMerger {
    fun merge(
        localDaily: List<DailyStepEntity>,
        localHourly: List<HourlyStepEntity>,
        snapshot: BackupSnapshot,
        effectiveProfile: UserProfile
    ): MergedBackupResult {
        val localDailyByDate = localDaily.associateBy { it.date }
        val backupDailyByDate = snapshot.dailySteps
            .filter { BackupSerializer.isValidIsoDate(it.date) }
            .associateBy { it.date }

        val localHourlyByKey = localHourly
            .filter { BackupSerializer.isValidIsoDate(it.date) && it.hour in 0..23 }
            .associateBy { it.date to it.hour }

        val mergedHourlyMap = localHourlyByKey.toMutableMap()
        val changedHourlyKeys = LinkedHashSet<Pair<String, Int>>()

        for (backupHour in snapshot.hourlySteps) {
            if (!BackupSerializer.isValidIsoDate(backupHour.date) || backupHour.hour !in 0..23) continue
            val key = backupHour.date to backupHour.hour
            val existing = mergedHourlyMap[key]
            val localSteps = existing?.steps ?: 0
            val backupSteps = backupHour.steps.coerceAtLeast(0)
            val mergedSteps = maxOf(localSteps, backupSteps)
            if (existing == null || mergedSteps > existing.steps) {
                mergedHourlyMap[key] = HourlyStepEntity(
                    date = backupHour.date,
                    hour = backupHour.hour,
                    steps = mergedSteps
                )
                changedHourlyKeys.add(key)
            }
        }
        val hourlyUpserts = changedHourlyKeys.map { mergedHourlyMap.getValue(it) }

        val mergedHourlySumByDate = mergedHourlyMap.values
            .groupBy { it.date }
            .mapValues { (_, entries) ->
                entries.sumOf { it.steps.toLong() }
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            }

        val allDates = (localDailyByDate.keys + backupDailyByDate.keys + mergedHourlySumByDate.keys).sorted()
        val dailyUpserts = mutableListOf<DailyStepEntity>()
        val strideMeters = effectiveProfile.effectiveStrideLengthMeters

        for (date in allDates) {
            val local = localDailyByDate[date]
            val backup = backupDailyByDate[date]
            val localSteps = local?.steps ?: 0
            val backupSteps = backup?.steps?.coerceAtLeast(0) ?: 0
            val hourlySum = mergedHourlySumByDate[date] ?: 0
            val mergedSteps = maxOf(localSteps, backupSteps, hourlySum)

            val resolvedGoal = local?.goalSteps?.takeIf { it in 1_000..100_000 }
                ?: backup?.goalSteps?.takeIf { it in 1_000..100_000 }
                ?: effectiveProfile.dailyGoal.coerceIn(1_000, 100_000)

            val hourlySumWon = hourlySum > maxOf(localSteps, backupSteps)
            val backupWon = !hourlySumWon && backup != null && backupSteps > localSteps

            val distanceMeters: Float
            val caloriesKcal: Float
            val activeDurationSeconds: Long

            when {
                hourlySumWon -> {
                    distanceMeters = StepCalculationEngine.calculateDistanceKm(mergedSteps, strideMeters) * 1000f
                    caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(
                        mergedSteps,
                        effectiveProfile.weightKg,
                        strideMeters
                    )
                    activeDurationSeconds = StepCalculationEngine.calculateActiveSeconds(mergedSteps)
                }
                backupWon -> {
                    distanceMeters = backup.distanceMeters.takeIf { it.isFinite() && (mergedSteps == 0 || it > 0f) }
                        ?: (StepCalculationEngine.calculateDistanceKm(mergedSteps, strideMeters) * 1000f)
                    caloriesKcal = backup.caloriesKcal.takeIf { it.isFinite() && (mergedSteps == 0 || it > 0f) }
                        ?: StepCalculationEngine.calculateCaloriesKcal(
                            mergedSteps,
                            effectiveProfile.weightKg,
                            strideMeters
                        )
                    activeDurationSeconds = backup.activeDurationSeconds.takeIf { mergedSteps == 0 || it > 0L }
                        ?: StepCalculationEngine.calculateActiveSeconds(mergedSteps)
                }
                local != null -> {
                    distanceMeters = local.distanceMeters
                    caloriesKcal = local.caloriesKcal
                    activeDurationSeconds = local.activeDurationSeconds
                }
                else -> {
                    distanceMeters = StepCalculationEngine.calculateDistanceKm(mergedSteps, strideMeters) * 1000f
                    caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(
                        mergedSteps,
                        effectiveProfile.weightKg,
                        strideMeters
                    )
                    activeDurationSeconds = StepCalculationEngine.calculateActiveSeconds(mergedSteps)
                }
            }

            val mergedEntity = DailyStepEntity(
                date = date,
                steps = mergedSteps,
                distanceMeters = distanceMeters,
                caloriesKcal = caloriesKcal,
                activeDurationSeconds = activeDurationSeconds,
                goalSteps = resolvedGoal
            )

            if (local == null || mergedEntity != local) {
                dailyUpserts.add(mergedEntity)
            }
        }

        return MergedBackupResult(
            dailyUpserts = dailyUpserts,
            hourlyUpserts = hourlyUpserts,
            mergedDaysCount = allDates.size,
            updatedDaysCount = dailyUpserts.size,
            updatedHourlyCount = hourlyUpserts.size
        )
    }
}
