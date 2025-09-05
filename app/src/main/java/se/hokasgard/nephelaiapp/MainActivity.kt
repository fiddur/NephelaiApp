package se.hokasgard.nephelaiapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.* // Covers all specific Record types used in HealthConnectScreen
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json // Ktor json plugin
import kotlinx.coroutines.CancellationException // Import CancellationException
import kotlinx.coroutines.launch
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NephelaiAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HealthConnectScreen()
                }
            }
        }
    }
}

@Composable
fun HealthConnectScreen() {
    val context = LocalContext.current
    val healthConnectClient = remember { HealthConnectClient.getOrCreate(context) }
    var hasPermissions by remember { mutableStateOf(false) }
    var healthRecords by remember { mutableStateOf<List<Record>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // allRecordTypes is now defined in HealthDataModels.kt in the same package
    val permissions = allRecordTypes.map {
        HealthPermission.getReadPermission(it)
    }.toSet()

    val ktorHttpClient = remember {
        HttpClient(Android) {
            install(ContentNegotiation) {
                // appJson is now defined in HealthDataModels.kt in the same package
                json(appJson)
            }
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        if (permissionsMap.values.all { it }) {
            Log.d("HealthConnect", "All permissions granted")
            hasPermissions = true
        } else {
            Log.d("HealthConnect", "Not all permissions were granted")
            hasPermissions = false
        }
    }

    suspend fun checkAndRequestPermissions() {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) {
            Log.d("HealthConnect", "Permissions are already granted.")
            hasPermissions = true
        } else {
            Log.d("HealthConnect", "Launching permission request.")
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    suspend fun readAllHealthData() {
        if (hasPermissions) {
            val sevenDaysAgo = ZonedDateTime.now().minusDays(7).toInstant()
            val now = Instant.now()
            val allFetchedRecords = mutableListOf<Record>()

            // allRecordTypes is used here
            for (recordType in allRecordTypes) {
                try {
                    val request = ReadRecordsRequest(
                        recordType = recordType,
                        timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo, now),
                        ascendingOrder = false
                    )
                    val response = healthConnectClient.readRecords(request)
                    allFetchedRecords.addAll(response.records)
                    Log.d("HealthConnect", "${recordType.simpleName} records read: ${response.records.size}")
                } catch (e: CancellationException) { // Catch specific CancellationException
                    Log.w("HealthConnect", "Reading ${recordType.simpleName} was cancelled. This might be due to scope leaving composition.", e)
                    throw e // Re-throw CancellationException
                } catch (e: Exception) {
                    Log.e("HealthConnect", "Error reading ${recordType.simpleName} records (non-cancellation)", e)
                    // Consider if you want to continue reading other record types or stop altogether
                }
            }
            healthRecords = allFetchedRecords.sortedByDescending { it.metadata.lastModifiedTime }
            Log.d("HealthConnect", "Total records read: ${healthRecords.size}")
        }
    }

    suspend fun sendAllHealthDataToServer(records: List<Record>) {
        if (records.isEmpty()) {
            Log.d("SendData", "No records to send.")
            return
        }
        if (BuildConfig.NEPHELIAI_API_TOKEN.isEmpty()) {
            Log.e("SendData", "API token is missing. Please check local.properties.")
            return
        }

        isSending = true
        val groupedRecords = records.groupBy { it::class }

        for ((recordClass, classRecords) in groupedRecords) {
            if (classRecords.isEmpty()) continue

            val recordTypeSimpleName = recordClass.simpleName ?: "UnknownRecordType"
            val apiUrl = "http://valhall/api/v2/sync/$recordTypeSimpleName"
            Log.d("SendData", "Processing ${classRecords.size} records of type $recordTypeSimpleName for $apiUrl")

            try {
                // Serializable classes (HrvRecordSerializable, etc.) and helper functions
                // (toSerializable, toIsoString) are now in HealthDataModels.kt
                when (recordClass) {
                    HeartRateVariabilityRmssdRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<HeartRateVariabilityRmssdRecord>().map { record ->
                            HrvRecordSerializable(
                                time = record.time.toIsoString(),
                                heartRateVariability = record.heartRateVariabilityMillis,
                                metadata = record.metadata.toSerializable()
                            )
                        }
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(HrvRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    WeightRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<WeightRecord>().map { record ->
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
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(WeightRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    StepsRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<StepsRecord>().map { record ->
                            StepsRecordSerializable(
                                count = record.count,
                                startTime = record.startTime.toIsoString(),
                                endTime = record.endTime.toIsoString(),
                                metadata = record.metadata.toSerializable()
                            )
                        }
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(StepsRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    HeartRateRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<HeartRateRecord>().map { record ->
                            HeartRateRecordSerializable(
                                startTime = record.startTime.toIsoString(),
                                endTime = record.endTime.toIsoString(),
                                samples = record.samples.map {
                                    HeartRateSampleSerializable(time = it.time.toIsoString(), beatsPerMinute = it.beatsPerMinute)
                                },
                                metadata = record.metadata.toSerializable()
                            )
                        }
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(HeartRateRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    ExerciseSessionRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<ExerciseSessionRecord>().map { record ->
                            ExerciseSessionRecordSerializable(
                                startTime = record.startTime.toIsoString(),
                                endTime = record.endTime.toIsoString(),
                                exerciseType = record.exerciseType,
                                title = record.title,
                                notes = record.notes,
                                segments = record.segments.map {
                                    ExerciseSegmentSerializable(
                                        startTime = it.startTime.toIsoString(),
                                        endTime = it.endTime.toIsoString(),
                                        segmentType = it.segmentType
                                    )
                                }.takeIf { it.isNotEmpty() },
                                laps = record.laps.map {
                                    ExerciseLapSerializable(
                                        startTime = it.startTime.toIsoString(),
                                        endTime = it.endTime.toIsoString(),
                                        lengthInMeters = it.length?.inMeters
                                    )
                                }.takeIf { it.isNotEmpty() },
                                route = if (record.exerciseRouteResult is ExerciseRouteResult.Data) {
                                    (record.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute?.let { sdkExerciseRoute: ExerciseRoute ->
                                        ExerciseRouteSerializable(
                                            route = sdkExerciseRoute.route.map { sdkLocation: ExerciseRoute.Location -> // Explicit type for sdkLocation
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
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(ExerciseSessionRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    DistanceRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<DistanceRecord>().map { record ->
                            DistanceRecordSerializable(
                                startTime = record.startTime.toIsoString(),
                                endTime = record.endTime.toIsoString(),
                                distanceInMeters = record.distance.inMeters,
                                metadata = record.metadata.toSerializable()
                            )
                        }
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(DistanceRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    SpeedRecord::class -> {
                        val serializableData = classRecords.filterIsInstance<SpeedRecord>().map { record ->
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
                        if (serializableData.isNotEmpty()) {
                            val postData = PostWrapper(serializableData)
                            Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(SpeedRecordSerializable.serializer()), postData)}")
                            ktorHttpClient.post(apiUrl) {
                                contentType(ContentType.Application.Json)
                                headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
                                setBody(postData)
                            }.also {
                                Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
                            }
                        }
                    }
                    else -> {
                        Log.w("SendData", "No specific serialization (aligned with react-native-health-connect) implemented for $recordTypeSimpleName. Skipping.")
                    }
                }
            } catch (e: Exception) {
                Log.e("SendData", "Error sending $recordTypeSimpleName data", e)
            }
        }
        isSending = false
    }

    LaunchedEffect(Unit) {
        checkAndRequestPermissions()
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            readAllHealthData()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (hasPermissions) "✅ Permissions Granted for Health Data" else "❌ Permissions Not Granted for Health Data"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        checkAndRequestPermissions()
                        if (hasPermissions) {
                            readAllHealthData()
                        }
                    }
                }
            ) {
                Text(if (hasPermissions) "Refresh Health Data" else "Check Permissions")
            }
            Button(
                onClick = { scope.launch { sendAllHealthDataToServer(healthRecords) } },
                enabled = healthRecords.isNotEmpty() && !isSending
            ) {
                Text(if (isSending) "Sending..." else "Send All Data to API")
            }
        }

        if (healthRecords.isNotEmpty()) {
            Text("Found ${healthRecords.size} Health Records (last 7 days):")
            LazyColumn {
                items(healthRecords) { record ->
                    // toIsoString() is now in HealthDataModels.kt
                    when (record) {
                        is HeartRateVariabilityRmssdRecord -> {
                            Text("HRV: ${record.heartRateVariabilityMillis} ms at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is WeightRecord -> {
                            Text("Weight: ${record.weight.inKilograms} kg at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is StepsRecord -> {
                             Text("Steps: ${record.count} from ${record.startTime.toIsoString()} to ${record.endTime.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is ExerciseSessionRecord -> {
                            Text("Exercise: ${record.title ?: record.exerciseType.toString().lowercase().replaceFirstChar { it.uppercase() }} (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is DistanceRecord -> {
                            Text("Distance: ${String.format("%.2f", record.distance.inMeters)}m (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is SpeedRecord -> {
                             Text("Speed: First sample ${String.format("%.2f", record.samples.firstOrNull()?.speed?.inMetersPerSecond ?: 0.0)} m/s (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        }
                        is HeartRateRecord -> {
                             Text("HeartRate: ${record.samples.size} samples, first ${record.samples.firstOrNull()?.beatsPerMinute ?: "N/A"}bpm (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        }
                        else -> {
                            Text("${record::class.simpleName}: ID: ${record.metadata.id.substring(0,8)} at ${record.metadata.lastModifiedTime.toIsoString()}")
                        }
                    }
                }
            }
        } else if (hasPermissions) {
            Text("No health records found for the last 7 days.")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NephelaiAppTheme {
        HealthConnectScreen()
    }
}
