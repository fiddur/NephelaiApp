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
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers // Required for headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders // Required for HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Shared Json configuration
private val appJson = Json {
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// Data classes for JSON serialization
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

@Serializable
data class HrvRecordSerializable(
    val metadata: HealthConnectRecordMetadata,
    val time: String, // ISO 8601 string for record time
    val heartRateVariabilityMillis: Double
)

@Serializable
data class HrvPostData(val data: List<HrvRecordSerializable>)

// Helper to format Instant to ISO 8601 String
fun Instant.toIsoString(): String {
    return this.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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
    var hrvRecords by remember { mutableStateOf<List<HeartRateVariabilityRmssdRecord>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    val ktorHttpClient = remember {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(appJson) // Use the shared appJson instance
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

    suspend fun readHrvRecords() {
        if (hasPermissions) {
            try {
                val sevenDaysAgo = ZonedDateTime.now().minusDays(7).toInstant()
                val now = Instant.now()
                val request = ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo, now),
                    ascendingOrder = false
                )
                val response = healthConnectClient.readRecords(request)
                hrvRecords = response.records
                Log.d("HealthConnect", "HRV records read: ${hrvRecords.size}")
            } catch (e: Exception) {
                Log.e("HealthConnect", "Error reading HRV records", e)
            }
        }
    }

    suspend fun sendHrvDataToServer(records: List<HeartRateVariabilityRmssdRecord>) {
        if (records.isEmpty()) {
            Log.d("SendData", "No records to send.")
            return
        }
        if (BuildConfig.NEPHELIAI_API_TOKEN.isEmpty()) {
            Log.e("SendData", "API token is missing. Please check local.properties.")
            isSending = false
            return
        }
        isSending = true
        val serializableRecords = records.map { record ->
            val hcMetadata = record.metadata
            val serializableMetadata = HealthConnectRecordMetadata(
                id = hcMetadata.id,
                dataOrigin = hcMetadata.dataOrigin.packageName,
                lastModifiedTime = hcMetadata.lastModifiedTime.toIsoString(),
                clientRecordId = hcMetadata.clientRecordId,
                clientRecordVersion = hcMetadata.clientRecordVersion,
                device = hcMetadata.device?.let {
                    DeviceSerializable(
                        manufacturer = it.manufacturer,
                        model = it.model,
                        type = it.type
                    )
                },
                recordingMethod = hcMetadata.recordingMethod
            )

            HrvRecordSerializable(
                metadata = serializableMetadata,
                time = record.time.toIsoString(),
                heartRateVariabilityMillis = record.heartRateVariabilityMillis
            )
        }
        val postData = HrvPostData(data = serializableRecords)

        val apiUrl = "http://valhall/api/v2/sync/HeartRateVariabilityRmssdRecord"
        Log.d("SendData", "Preparing to send ${postData.data.size} records to $apiUrl")

        Log.d("SendData", "JSON Body: ${appJson.encodeToString(HrvPostData.serializer(), postData)}")

        try {
            val response = ktorHttpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}")
                }
                setBody(postData)
            }
            Log.d("SendData", "Server response: ${response.status} - ${response.bodyAsText()}")
        } catch (e: Exception) {
            Log.e("SendData", "Error sending HRV data", e)
        } finally {
            isSending = false
        }
    }

    LaunchedEffect(Unit) {
        checkAndRequestPermissions()
    }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            readHrvRecords()
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
            if (hasPermissions) "✅ Permissions Granted for HRV" else "❌ Permissions Not Granted for HRV"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        checkAndRequestPermissions()
                        if (hasPermissions) {
                            readHrvRecords()
                        }
                    }
                }
            ) {
                Text(if (hasPermissions) "Refresh HRV Data" else "Check Permissions")
            }
            Button(
                onClick = { scope.launch { sendHrvDataToServer(hrvRecords) } },
                enabled = hrvRecords.isNotEmpty() && !isSending
            ) {
                Text(if (isSending) "Sending..." else "Send to API")
            }
        }

        if (hrvRecords.isNotEmpty()) {
            Text("Latest HRV Records (last 7 days):")
            LazyColumn {
                items(hrvRecords) { record ->
                    Text("Time: ${record.time.toIsoString()}, HRV: ${record.heartRateVariabilityMillis} ms, ID: ${record.metadata.id}")
                }
            }
        } else if (hasPermissions) {
            Text("No HRV records found for the last 7 days.")
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
