package se.hokasgard.nephelaiapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ChangesTokenRequest
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.reflect.KClass

private const val PREFS_NAME = "NephelaiAppPrefs"
private const val CHANGES_TOKEN_KEY = "healthConnectChangesToken"

private fun saveChangesToken(context: Context, token: String?) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    Log.d("TokenManager", "Saving token: ${token?.take(10)}...")
    prefs.edit().putString(CHANGES_TOKEN_KEY, token).apply()
}

private fun loadChangesToken(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val token = prefs.getString(CHANGES_TOKEN_KEY, null)
    Log.d("TokenManager", "Loaded token: ${token?.take(10)}...")
    return token
}

private suspend inline fun <reified T : Any> handlePostData(
    dataList: List<T>,
    itemSerializer: KSerializer<T>,
    apiUrl: String,
    recordTypeSimpleName: String,
    httpClient: HttpClient
): Boolean {
    if (dataList.isEmpty()) {
        Log.d("SendData", "No data to send for $recordTypeSimpleName")
        return true
    }
    val postData = PostWrapper(dataList)
    Log.d("SendData", "JSON Body for $recordTypeSimpleName: ${appJson.encodeToString(PostWrapper.serializer(itemSerializer), postData)}")
    try {
        val response = httpClient.post(apiUrl) {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer ${BuildConfig.NEPHELIAI_API_TOKEN}") }
            setBody(postData)
        }
        Log.d("SendData", "$recordTypeSimpleName Server response: ${response.status} - ${response.bodyAsText()}")
        return response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created
    } catch (e: Exception) {
        Log.e("SendData", "Error posting $recordTypeSimpleName data to $apiUrl", e)
        return false
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
    var isProcessing by remember { mutableStateOf(false) }
    var pendingTokenToPersist by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Request permissions to begin.") }

    val scope = rememberCoroutineScope()
    val permissions = allRecordTypes.map { HealthPermission.getReadPermission(it) }.toSet()
    val ktorHttpClient = remember { HttpClient(Android) { install(ContentNegotiation) { json(appJson) } } }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        hasPermissions = permissionsMap.values.all { it }
        if (hasPermissions) {
            Log.d("HealthConnect", "All permissions granted")
            statusMessage = "Permissions granted. Fetch data from Health Connect."
        } else {
            Log.d("HealthConnect", "Not all permissions were granted")
            statusMessage = "Permissions not granted. Health data cannot be accessed."
        }
    }

    suspend fun checkAndRequestPermissions() {
        isProcessing = true
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) {
            hasPermissions = true
            statusMessage = "Permissions already granted. Fetch data."
            Log.d("HealthConnect", "Permissions are already granted.")
        } else {
            Log.d("HealthConnect", "Launching permission request.")
            statusMessage = "Requesting permissions..."
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
        isProcessing = false
    }

    suspend fun fetchHealthData(currentActiveContext: Context) {
        if (!hasPermissions) {
            statusMessage = "Permissions not granted. Cannot fetch data."
            return
        }
        isProcessing = true
        statusMessage = "Fetching data from Health Connect..."
        healthRecords = emptyList()
        pendingTokenToPersist = null
        var fetchSuccessful = true

        val lastTokenFromPrefs = loadChangesToken(currentActiveContext)

        if (lastTokenFromPrefs == null) {
            Log.d("FetchData", "No token found. Performing initial fetch.")
            try {
                val sevenDaysAgo = ZonedDateTime.now().minusDays(7).toInstant()
                val now = Instant.now()
                val initialFetchedRecords = mutableListOf<Record>()
                for (recordType: KClass<out Record> in allRecordTypes) {
                    @Suppress("UNCHECKED_CAST")
                    val specificRecordType = recordType as KClass<Record>
                    val request = ReadRecordsRequest(
                        recordType = specificRecordType,
                        timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo, now),
                        ascendingOrder = false
                    )
                    initialFetchedRecords.addAll(healthConnectClient.readRecords(request).records)
                }
                val sortedRecords = initialFetchedRecords.sortedByDescending { it.metadata.lastModifiedTime }
                healthRecords = sortedRecords
                Log.d("FetchData", "Initial fetch complete: ${healthRecords.size} records.")
                if (healthRecords.isNotEmpty()) {
                    val initialToken = healthConnectClient.getChangesToken(ChangesTokenRequest(allRecordTypes.toSet()))
                    pendingTokenToPersist = initialToken
                    statusMessage = "Fetched ${healthRecords.size} initial records. Ready to send."
                } else {
                    statusMessage = "No records found during initial fetch."
                    try {
                        val initialToken = healthConnectClient.getChangesToken(ChangesTokenRequest(allRecordTypes.toSet()))
                        saveChangesToken(currentActiveContext, initialToken) // Save immediately as there's nothing to send
                        Log.d("FetchData", "Saved initial token as no data was found: ${initialToken.take(10)}...")
                        pendingTokenToPersist = null // Ensure it's not re-saved on send
                    } catch (e: Exception) {
                        Log.e("FetchData", "Failed to get/save initial changes token when no initial data found.", e)
                        statusMessage = "Error initializing token with no data."
                    }
                }
            } catch (e: Exception) {
                Log.e("FetchData", "Error fetching initial data from Health Connect.", e)
                statusMessage = "Error fetching initial data: ${e.message}"
                fetchSuccessful = false
            }
        } else { // Token exists, fetch changes
            Log.d("FetchData", "Token found: ${lastTokenFromPrefs.take(10)}... Fetching changes.")
            try {
                val changesResponse = healthConnectClient.getChanges(lastTokenFromPrefs)
                val upsertedRecords = changesResponse.changes.mapNotNull { if (it is UpsertionChange) it.record else null }
                changesResponse.changes.forEach { if (it is DeletionChange) Log.d("HealthConnect", "Record deleted, ID: ${it.recordId}. Deletion handling for server not implemented.") }
                healthRecords = upsertedRecords.sortedByDescending { it.metadata.lastModifiedTime }
                pendingTokenToPersist = changesResponse.nextChangesToken
                Log.d("FetchData", "Fetched ${healthRecords.size} upsertions. Next token candidate: ${pendingTokenToPersist?.take(10)}...")
                if (healthRecords.isEmpty()) {
                    statusMessage = "No new changes found."
                    saveChangesToken(currentActiveContext, pendingTokenToPersist) // Save next token as HC state advanced
                    Log.d("FetchData", "Saved next changes token as no new data was found: ${pendingTokenToPersist?.take(10)}...")
                    pendingTokenToPersist = null // Ensure it's not re-saved on send
                } else {
                    statusMessage = "Fetched ${healthRecords.size} new/updated records. Ready to send."
                }
            } catch (e: Exception) {
                Log.e("FetchData", "Error fetching changes from Health Connect.", e)
                statusMessage = "Error fetching changes: ${e.message}"
                fetchSuccessful = false
            }
        }
        if (!fetchSuccessful) healthRecords = emptyList() // Clear records if fetch failed
        isProcessing = false
    }

    suspend fun sendPendingDataToServer(currentActiveContext: Context) {
        if (BuildConfig.NEPHELIAI_API_TOKEN.isEmpty()) {
            statusMessage = "API token is missing. Cannot send."
            Log.e("SendData", "API token is missing.")
            return
        }
        if (healthRecords.isEmpty()) {
            statusMessage = "No records to send."
            Log.d("SendData", "No records to send.")
            return
        }

        isProcessing = true
        statusMessage = "Sending ${healthRecords.size} records to server..."
        var allPostsSuccessful = true

        val groupedRecords = healthRecords.groupBy { it::class }
        for ((recordClass, classRecords) in groupedRecords) {
            if (classRecords.isEmpty()) continue
            val recordTypeSimpleName = recordClass.simpleName ?: "UnknownRecordType"
            val apiUrl = "http://valhall/api/v2/sync/$recordTypeSimpleName"
            Log.d("SendData", "Processing ${classRecords.size} records of type $recordTypeSimpleName for $apiUrl")

            val postSuccessful = when (recordClass) {
                HeartRateVariabilityRmssdRecord::class -> handlePostData(HrvRecordSerializable.fromRecordsList(classRecords), HrvRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                WeightRecord::class -> handlePostData(WeightRecordSerializable.fromRecordsList(classRecords), WeightRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                StepsRecord::class -> handlePostData(StepsRecordSerializable.fromRecordsList(classRecords), StepsRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                // ... (all other when cases from your previous code)
                HeartRateRecord::class -> handlePostData(HeartRateRecordSerializable.fromRecordsList(classRecords), HeartRateRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                ExerciseSessionRecord::class -> handlePostData(ExerciseSessionRecordSerializable.fromRecordsList(classRecords), ExerciseSessionRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                DistanceRecord::class -> handlePostData(DistanceRecordSerializable.fromRecordsList(classRecords), DistanceRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                SpeedRecord::class -> handlePostData(SpeedRecordSerializable.fromRecordsList(classRecords), SpeedRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                ActiveCaloriesBurnedRecord::class -> handlePostData(ActiveCaloriesBurnedRecordSerializable.fromRecordsList(classRecords), ActiveCaloriesBurnedRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                TotalCaloriesBurnedRecord::class -> handlePostData(TotalCaloriesBurnedRecordSerializable.fromRecordsList(classRecords), TotalCaloriesBurnedRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                PowerRecord::class -> handlePostData(PowerRecordSerializable.fromRecordsList(classRecords), PowerRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                NutritionRecord::class -> handlePostData(NutritionRecordSerializable.fromRecordsList(classRecords), NutritionRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                LeanBodyMassRecord::class -> handlePostData(LeanBodyMassRecordSerializable.fromRecordsList(classRecords), LeanBodyMassRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                BodyFatRecord::class -> handlePostData(BodyFatRecordSerializable.fromRecordsList(classRecords), BodyFatRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                SleepSessionRecord::class -> handlePostData(SleepSessionRecordSerializable.fromRecordsList(classRecords), SleepSessionRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                BoneMassRecord::class -> handlePostData(BoneMassRecordSerializable.fromRecordsList(classRecords), BoneMassRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                else -> { Log.w("SendData", "No specific serialization for $recordTypeSimpleName. Skipping."); true }
            }
            if (!postSuccessful) {
                allPostsSuccessful = false
                statusMessage = "Failed to send $recordTypeSimpleName. Other data might be pending."
                Log.w("SendData", "Post failed for $recordTypeSimpleName. Aborting token update for this sync batch.")
                break
            }
        }

        if (allPostsSuccessful) {
            if (pendingTokenToPersist != null) {
                saveChangesToken(currentActiveContext, pendingTokenToPersist)
                statusMessage = "Data sent successfully. Token updated."
                Log.d("SendData", "All posts successful. Saved token: ${pendingTokenToPersist?.take(10)}...")
                pendingTokenToPersist = null // Clear after successful persistence
            } else {
                // This case should ideally not be hit if fetch logic is correct and populates pendingTokenToPersist
                statusMessage = "Data sent successfully, but no new token was pending to save."
                Log.w("SendData", "All posts successful but no pendingTokenToPersist was set.")
            }
            healthRecords = emptyList() // Clear UI on successful send of this batch
        } else {
            statusMessage = "Failed to send some data. Pending records remain. Try sending again."
            Log.w("SendData", "Not all posts in this batch were successful. Old token (if any) is preserved. Pending records and their associated token candidate remain.")
        }
        isProcessing = false
    }

    LaunchedEffect(Unit) {
        checkAndRequestPermissions()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(statusMessage)
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasPermissions) {
            Button(
                onClick = { scope.launch { checkAndRequestPermissions() } },
                enabled = !isProcessing
            ) {
                Text("Request Permissions")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { fetchHealthData(context) } },
                    enabled = !isProcessing
                ) {
                    Text("Fetch New Data")
                }
                Button(
                    onClick = { scope.launch { sendPendingDataToServer(context) } },
                    enabled = healthRecords.isNotEmpty() && !isProcessing
                ) {
                    Text("Send Pending Data")
                }
            }
        }

        if (healthRecords.isNotEmpty()) {
            Text("Pending records to send: ${healthRecords.size}")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(healthRecords) { record ->
                    // Same when(record) block as before
                    when (record) {
                        is HeartRateVariabilityRmssdRecord -> Text("HRV: ${record.heartRateVariabilityMillis} ms at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        is WeightRecord -> Text("Weight: ${record.weight.inKilograms} kg at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        is StepsRecord -> Text("Steps: ${record.count} from ${record.startTime.toIsoString()} to ${record.endTime.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        is ExerciseSessionRecord -> Text("Exercise: ${record.title ?: record.exerciseType.toString().lowercase().replaceFirstChar { it.uppercase() }} (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is DistanceRecord -> Text("Distance: ${String.format("%.2f", record.distance.inMeters)}m (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is SpeedRecord -> Text("Speed: First sample ${String.format("%.2f", record.samples.firstOrNull()?.speed?.inMetersPerSecond ?: 0.0)} m/s (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is HeartRateRecord -> Text("HeartRate: ${record.samples.size} samples, first ${record.samples.firstOrNull()?.beatsPerMinute ?: "N/A"}bpm (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is ActiveCaloriesBurnedRecord -> Text("Active Cals: ${String.format("%.2f", record.energy.inKilocalories)} kcal (${record.startTime.toIsoString()} - ${record.endTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is TotalCaloriesBurnedRecord -> Text("Total Cals: ${String.format("%.2f", record.energy.inKilocalories)} kcal (${record.startTime.toIsoString()} - ${record.endTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is PowerRecord -> Text("Power: ${record.samples.size} samples, first ${String.format("%.2f", record.samples.firstOrNull()?.power?.inWatts ?: 0.0)}W (${record.startTime.toIsoString()}), ID: ${record.metadata.id.substring(0,8)}")
                        is NutritionRecord -> Text("Nutrition: ${record.name ?: "Unnamed food"} (${record.mealType}, ${String.format("%.0f",record.energy?.inKilocalories ?: 0.0)} kcal), ID: ${record.metadata.id.substring(0,8)}")
                        is LeanBodyMassRecord -> Text("Lean Body Mass: ${String.format("%.2f", record.mass.inKilograms)} kg at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        is BodyFatRecord -> Text("Body Fat: ${String.format("%.1f", record.percentage.value)}%% at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        is SleepSessionRecord -> Text("Sleep: ${record.title ?: "Session"} (${record.startTime.toIsoString()} - ${record.endTime.toIsoString()}), Stages: ${record.stages.size}, ID: ${record.metadata.id.substring(0,8)}")
                        is BoneMassRecord -> Text("Bone Mass: ${String.format("%.2f", record.mass.inKilograms)} kg at ${record.time.toIsoString()}, ID: ${record.metadata.id.substring(0,8)}")
                        else -> Text("${record::class.simpleName}: ID: ${record.metadata.id.substring(0,8)} at ${record.metadata.lastModifiedTime.toIsoString()}")
                    }
                }
            }
        } else if (hasPermissions && !isProcessing) {
            // Message handled by statusMessage
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HealthConnectScreenPreview() {
    NephelaiAppTheme {
        HealthConnectScreen()
    }
}
