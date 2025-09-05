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
import kotlinx.serialization.KSerializer
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.ZonedDateTime

// Helper function to handle posting data - moved to top-level
private suspend inline fun <reified T : Any> handlePostData(
    dataList: List<T>,
    itemSerializer: KSerializer<T>,
    apiUrl: String,
    recordTypeSimpleName: String,
    httpClient: HttpClient // Added httpClient parameter
) {
    if (dataList.isEmpty()) return

    val postData = PostWrapper(dataList)
    Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(itemSerializer), postData)}")

    httpClient.post(apiUrl) {
        contentType(ContentType.Application.Json)
        headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
        setBody(postData)
    }.also {
        Log.d("SendData", "$recordTypeSimpleName Server response: ${it.status} - ${it.bodyAsText()}")
    }
}

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

    val permissions = allRecordTypes.map {
        HealthPermission.getReadPermission(it)
    }.toSet()

    val ktorHttpClient = remember {
        HttpClient(Android) {
            install(ContentNegotiation) {
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
                } catch (e: CancellationException) {
                    Log.w("HealthConnect", "Reading ${recordType.simpleName} was cancelled. This might be due to scope leaving composition.", e)
                    throw e 
                } catch (e: Exception) {
                    Log.e("HealthConnect", "Error reading ${recordType.simpleName} records (non-cancellation)", e)
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
                when (recordClass) {
                    HeartRateVariabilityRmssdRecord::class -> {
                        val serializableData = HrvRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, HrvRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    WeightRecord::class -> {
                        val serializableData = WeightRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, WeightRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    StepsRecord::class -> {
                        val serializableData = StepsRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, StepsRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    HeartRateRecord::class -> {
                        val serializableData = HeartRateRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, HeartRateRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    ExerciseSessionRecord::class -> {
                        val serializableData = ExerciseSessionRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, ExerciseSessionRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    DistanceRecord::class -> {
                        val serializableData = DistanceRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, DistanceRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                    }
                    SpeedRecord::class -> {
                        val serializableData = SpeedRecordSerializable.fromRecordsList(classRecords)
                        handlePostData(serializableData, SpeedRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
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
