package se.hokasgard.nephelaiapp

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

// Shared Json configuration used by Ktor in MainActivity and for logging here
val appJson = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// --- Serializable Data Classes ---
@Serializable
data class DeviceSerializable(
    val manufacturer: String?,
    val model: String?,
    val type: Int
)

@Serializable
data class HealthConnectRecordMetadata(
    val id: String,
    val dataOrigin: String, // package name
    val lastModifiedTime: String, // ISO 8601 string
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val device: DeviceSerializable?,
    val recordingMethod: Int
)

// Generic wrapper for post data
@Serializable
data class PostWrapper<T>(val data: List<T>)


// --- Specific Serializable Record Types (aligned with react-native-health-connect) ---

@Serializable
data class WeightUnitOutput(
    val inKilograms: Double,
    val inGrams: Double,
    val inMilligrams: Double,
    val inMicrograms: Double,
    val inPounds: Double,
    val inOunces: Double
)

@Serializable
data class WeightRecordSerializable(
    val time: String,
    val weight: WeightUnitOutput,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<WeightRecordSerializable> {
            return classRecords.filterIsInstance<WeightRecord>().map { record ->
                WeightRecordSerializable(
                    time = record.time.toIsoString(),
                    weight = WeightUnitOutput(
                        inKilograms = record.weight.inKilograms,
                        inGrams = record.weight.inGrams,
                        inMilligrams = record.weight.inMilligrams,
                        inMicrograms = record.weight.inMicrograms,
                        inPounds = record.weight.inPounds,
                        inOunces = record.weight.inOunces
                    ),
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

@Serializable
data class HrvRecordSerializable(
    val time: String,
    val heartRateVariability: Double, // Renamed from heartRateVariabilityMillis
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<HrvRecordSerializable> {
            return classRecords.filterIsInstance<HeartRateVariabilityRmssdRecord>().map { record ->
                HrvRecordSerializable(
                    time = record.time.toIsoString(),
                    heartRateVariability = record.heartRateVariabilityMillis,
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

@Serializable
data class StepsRecordSerializable(
    val count: Long,
    val startTime: String,
    val endTime: String,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<StepsRecordSerializable> {
            return classRecords.filterIsInstance<StepsRecord>().map { record ->
                StepsRecordSerializable(
                    count = record.count,
                    startTime = record.startTime.toIsoString(),
                    endTime = record.endTime.toIsoString(),
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

@Serializable
data class HeartRateSampleSerializable(
    val time: String,
    val beatsPerMinute: Long
)

@Serializable
data class HeartRateRecordSerializable(
    val startTime: String,
    val endTime: String,
    val samples: List<HeartRateSampleSerializable>,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<HeartRateRecordSerializable> {
            return classRecords.filterIsInstance<HeartRateRecord>().map { record ->
                HeartRateRecordSerializable(
                    startTime = record.startTime.toIsoString(),
                    endTime = record.endTime.toIsoString(),
                    samples = record.samples.map {
                        HeartRateSampleSerializable(time = it.time.toIsoString(), beatsPerMinute = it.beatsPerMinute)
                    },
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

// -- Exercise Session Data Classes --
@Serializable
data class ExerciseSegmentSerializable(
    val startTime: String,
    val endTime: String,
    val segmentType: Int
)

@Serializable
data class ExerciseLapSerializable(
    val startTime: String,
    val endTime: String,
    val lengthInMeters: Double?
)

@Serializable
data class ExerciseRouteLocationSerializable(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyInMeters: Double?,
    val verticalAccuracyInMeters: Double?,
    val altitudeInMeters: Double?
)

@Serializable
data class ExerciseRouteSerializable(
    val route: List<ExerciseRouteLocationSerializable>
)

@Serializable
data class ExerciseSessionRecordSerializable(
    val startTime: String,
    val endTime: String,
    val exerciseType: Int,
    val title: String? = null,
    val notes: String? = null,
    val segments: List<ExerciseSegmentSerializable>? = null,
    val laps: List<ExerciseLapSerializable>? = null,
    val route: ExerciseRouteSerializable? = null,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<ExerciseSessionRecordSerializable> {
            return classRecords.filterIsInstance<ExerciseSessionRecord>().map { record: ExerciseSessionRecord ->
                ExerciseSessionRecordSerializable(
                    startTime = record.startTime.toIsoString(),
                    endTime = record.endTime.toIsoString(),
                    exerciseType = record.exerciseType,
                    title = record.title,
                    notes = record.notes,
                    segments = record.segments.map { segment: ExerciseSegment ->
                        ExerciseSegmentSerializable(
                            startTime = segment.startTime.toIsoString(),
                            endTime = segment.endTime.toIsoString(),
                            segmentType = segment.segmentType
                        )
                    }.takeIf { it.isNotEmpty() },
                    laps = record.laps.map { lap: ExerciseLap ->
                        ExerciseLapSerializable(
                            startTime = lap.startTime.toIsoString(),
                            endTime = lap.endTime.toIsoString(),
                            lengthInMeters = lap.length?.inMeters
                        )
                    }.takeIf { it.isNotEmpty() },
                    route = if (record.exerciseRouteResult is ExerciseRouteResult.Data) {
                        (record.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute?.let { sdkExerciseRoute: ExerciseRoute ->
                            ExerciseRouteSerializable(
                                route = sdkExerciseRoute.route.map { sdkLocation: ExerciseRoute.Location ->
                                    ExerciseRouteLocationSerializable(
                                        time = sdkLocation.time.toIsoString(),
                                        latitude = sdkLocation.latitude,
                                        longitude = sdkLocation.longitude,
                                        horizontalAccuracyInMeters = sdkLocation.horizontalAccuracy?.inMeters,
                                        verticalAccuracyInMeters = sdkLocation.verticalAccuracy?.inMeters,
                                        altitudeInMeters = sdkLocation.altitude?.inMeters
                                    )
                                }
                            )
                        }
                    } else {
                        null
                    },
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

// --- Distance Record --- 
@Serializable
data class DistanceRecordSerializable(
    val startTime: String,
    val endTime: String,
    val distanceInMeters: Double?,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<DistanceRecordSerializable> {
            return classRecords.filterIsInstance<DistanceRecord>().map { record ->
                DistanceRecordSerializable(
                    startTime = record.startTime.toIsoString(),
                    endTime = record.endTime.toIsoString(),
                    distanceInMeters = record.distance.inMeters,
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

// --- Speed Record ---
@Serializable
data class SpeedSampleSerializable(
    val time: String,
    val speedInMetersPerSecond: Double
)

@Serializable
data class SpeedRecordSerializable(
    val startTime: String,
    val endTime: String,
    val samples: List<SpeedSampleSerializable>,
    val metadata: HealthConnectRecordMetadata
) {
    companion object {
        fun fromRecordsList(classRecords: List<Record>): List<SpeedRecordSerializable> {
            return classRecords.filterIsInstance<SpeedRecord>().map { record ->
                SpeedRecordSerializable(
                    startTime = record.startTime.toIsoString(),
                    endTime = record.endTime.toIsoString(),
                    samples = record.samples.map {
                        SpeedSampleSerializable(
                            time = it.time.toIsoString(),
                            speedInMetersPerSecond = it.speed.inMetersPerSecond
                        )
                    },
                    metadata = record.metadata.toSerializable()
                )
            }
        }
    }
}

// Helper to format Instant to ISO 8601 String
fun Instant.toIsoString(): String {
    return this.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

// Helper to convert Health Connect Metadata to Serializable Metadata
fun Metadata.toSerializable(): HealthConnectRecordMetadata {
    return HealthConnectRecordMetadata(
        id = this.id,
        dataOrigin = this.dataOrigin.packageName,
        lastModifiedTime = this.lastModifiedTime.toIsoString(),
        clientRecordId = this.clientRecordId,
        clientRecordVersion = this.clientRecordVersion,
        device = this.device?.let {
            DeviceSerializable(
                manufacturer = it.manufacturer,
                model = it.model,
                type = it.type
            )
        },
        recordingMethod = this.recordingMethod
    )
}

// List of all record KClass objects we want to read
val allRecordTypes: List<KClass<out Record>> = listOf(
    WeightRecord::class,
    ExerciseSessionRecord::class,
    SpeedRecord::class,
    DistanceRecord::class,
    StepsRecord::class,
    ActiveCaloriesBurnedRecord::class,
    TotalCaloriesBurnedRecord::class,
    PowerRecord::class,
    NutritionRecord::class,
    HeartRateVariabilityRmssdRecord::class,
    HeartRateRecord::class,
    LeanBodyMassRecord::class,
    BodyFatRecord::class,
    SleepSessionRecord::class,
    BoneMassRecord::class
)
