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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

private const val PREFS_NAME = "NephelaiAppPrefs"
private const val CHANGES_TOKEN_KEY = "healthConnectChangesToken"

fun Record.getPrimaryInstant(): Instant {
    return when (this) {
        is StepsRecord -> this.endTime
        is DistanceRecord -> this.endTime
        is SpeedRecord -> this.endTime
        is ActiveCaloriesBurnedRecord -> this.endTime
        is TotalCaloriesBurnedRecord -> this.endTime
        is PowerRecord -> this.endTime
        is NutritionRecord -> this.endTime
        is SleepSessionRecord -> this.startTime
        is HeartRateVariabilityRmssdRecord -> this.time
        is WeightRecord -> this.time
        is LeanBodyMassRecord -> this.time
        is BodyFatRecord -> this.time
        is BoneMassRecord -> this.time
        is ExerciseSessionRecord -> this.startTime
        is HeartRateRecord -> this.startTime
        else -> this.metadata.lastModifiedTime
    }
}

fun getRecordSummary(record: Record): String {
    return when (record) {
        is HeartRateVariabilityRmssdRecord -> "HRV: ${record.heartRateVariabilityMillis} ms"
        is WeightRecord -> "Weight: ${record.weight.inKilograms} kg"
        is StepsRecord -> "Steps: ${record.count}"
        is ExerciseSessionRecord -> "Exercise: ${record.title ?: record.exerciseType.toString().lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}"
        is DistanceRecord -> "Distance: ${String.format("%.2f", record.distance.inMeters)}m"
        is SpeedRecord -> "Speed: First sample ${String.format("%.2f", record.samples.firstOrNull()?.speed?.inMetersPerSecond ?: 0.0)} m/s"
        is HeartRateRecord -> "HeartRate: ${record.samples.size} samples, first ${record.samples.firstOrNull()?.beatsPerMinute ?: "N/A"}bpm"
        is ActiveCaloriesBurnedRecord -> "Active Cals: ${String.format("%.2f", record.energy.inKilocalories)} kcal"
        is TotalCaloriesBurnedRecord -> "Total Cals: ${String.format("%.2f", record.energy.inKilocalories)} kcal"
        is PowerRecord -> "Power: ${record.samples.size} samples, first ${String.format("%.2f", record.samples.firstOrNull()?.power?.inWatts ?: 0.0)}W"
        is NutritionRecord -> "Nutrition: ${record.name ?: "Unnamed food"} (${record.mealType ?: "Unknown"}, ${String.format("%.0f",record.energy?.inKilocalories ?: 0.0)} kcal)"
        is LeanBodyMassRecord -> "Lean Body Mass: ${String.format("%.2f", record.mass.inKilograms)} kg"
        is BodyFatRecord -> "Body Fat: ${String.format("%.1f", record.percentage.value)}%%"
        is SleepSessionRecord -> "Sleep: ${record.title ?: "Session"} (Stages: ${record.stages.size})"
        is BoneMassRecord -> "Bone Mass: ${String.format("%.2f", record.mass.inKilograms)} kg"
        else -> record::class.simpleName ?: "Record"
    }
}

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val healthConnectClient = remember { HealthConnectClient.getOrCreate(context) }
    var hasPermissions by remember { mutableStateOf(false) }
    var healthRecords by remember { mutableStateOf<List<Record>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) } 
    var pendingTokenToPersist by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Checking permissions...") } // Initial status

    val scope = rememberCoroutineScope()
    val permissions = allRecordTypes.map { HealthPermission.getReadPermission(it) }.toSet()
    val ktorHttpClient = remember { HttpClient(Android) { install(ContentNegotiation) { json(appJson) } } }

    suspend fun fetchHealthData(currentActiveContext: Context) {
        if (!hasPermissions) {
            statusMessage = "Permissions not granted. Cannot fetch data."
            Log.d("HealthConnectScreen", "fetchHealthData called but no permissions.")
            return // isProcessing should be false if we reach here via this path
        }
        if(isProcessing) { // Prevent truly concurrent fetches
            Log.d("HealthConnectScreen", "fetchHealthData called while already processing (concurrent call). Bailing.")
            return
        }
        isProcessing = true // Set isProcessing for the duration of this fetch operation
        statusMessage = "Fetching data from Health Connect..."
        Log.d("HealthConnectScreen", "Starting data fetch...")
        val localHealthRecords = mutableListOf<Record>() 
        var localPendingTokenToPersist: String? = null 
        var fetchSuccessful = true

        val lastTokenFromPrefs = loadChangesToken(currentActiveContext)

        if (lastTokenFromPrefs == null) {
            Log.d("FetchData", "No token found. Performing initial fetch.")
            try {
                val sevenDaysAgo = ZonedDateTime.now().minusDays(7).toInstant()
                val now = Instant.now()
                for (recordType: KClass<out Record> in allRecordTypes) {
                    @Suppress("UNCHECKED_CAST")
                    val specificRecordType = recordType as KClass<Record>
                    val request = ReadRecordsRequest(
                        recordType = specificRecordType,
                        timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo, now),
                        ascendingOrder = false
                    )
                    localHealthRecords.addAll(healthConnectClient.readRecords(request).records)
                }
                Log.d("FetchData", "Initial fetch complete: ${localHealthRecords.size} records.")
                if (localHealthRecords.isNotEmpty()) {
                    val initialToken = healthConnectClient.getChangesToken(ChangesTokenRequest(allRecordTypes.toSet()))
                    localPendingTokenToPersist = initialToken
                    statusMessage = "Fetched ${localHealthRecords.size} initial records. Ready to send."
                } else {
                    statusMessage = "No records found during initial fetch."
                    try {
                        val initialToken = healthConnectClient.getChangesToken(ChangesTokenRequest(allRecordTypes.toSet()))
                        saveChangesToken(currentActiveContext, initialToken) 
                        Log.d("FetchData", "Saved initial token as no data was found: ${initialToken.take(10)}...")
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
        } else { 
            Log.d("FetchData", "Token found: ${lastTokenFromPrefs.take(10)}... Fetching changes.")
            try {
                val changesResponse = healthConnectClient.getChanges(lastTokenFromPrefs)
                changesResponse.changes.mapNotNull { if (it is UpsertionChange) it.record else null }.forEach { localHealthRecords.add(it) }
                changesResponse.changes.forEach { if (it is DeletionChange) Log.d("HealthConnect", "Record deleted, ID: ${it.recordId}. Deletion handling for server not implemented.") }
                localPendingTokenToPersist = changesResponse.nextChangesToken
                Log.d("FetchData", "Fetched ${localHealthRecords.size} upsertions. Next token candidate: ${localPendingTokenToPersist?.take(10)}...")
                if (localHealthRecords.isEmpty()) {
                    statusMessage = "No new changes found."
                    saveChangesToken(currentActiveContext, localPendingTokenToPersist) 
                    Log.d("FetchData", "Saved next changes token as no new data was found: ${localPendingTokenToPersist?.take(10)}...")
                    localPendingTokenToPersist = null
                } else {
                    statusMessage = "Fetched ${localHealthRecords.size} new/updated records. Ready to send."
                }
            } catch (e: Exception) {
                Log.e("FetchData", "Error fetching changes from Health Connect.", e)
                statusMessage = "Error fetching changes: ${e.message}"
                fetchSuccessful = false
            }
        }

        if (fetchSuccessful) {
            healthRecords = localHealthRecords.sortedByDescending { it.getPrimaryInstant() }
            pendingTokenToPersist = localPendingTokenToPersist 
        } else {
            healthRecords = emptyList() 
            pendingTokenToPersist = null
        }
        Log.d("HealthConnectScreen", "Data fetch processing finished. status: $statusMessage")
        isProcessing = false // Clear isProcessing at the end of the fetch operation
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        hasPermissions = permissionsMap.values.all { it }
        if (hasPermissions) {
            Log.d("HealthConnect", "All permissions granted via launcher. Attempting to fetch data.")
            // isProcessing should be false here, fetchHealthData will handle its own state
            scope.launch { fetchHealthData(context) } 
        } else {
            Log.d("HealthConnect", "Not all permissions were granted via launcher")
            statusMessage = "Permissions not granted. Health data cannot be accessed."
            isProcessing = false // Ensure isProcessing is false if permissions are denied
        }
    }

    suspend fun checkPermissionsAndFetchData(coroutineScope: CoroutineScope, currentContext: Context) {
        // statusMessage is "Checking permissions..." or whatever it was before this call
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(permissions)) {
            hasPermissions = true
            Log.d("HealthConnect", "Permissions are already granted. Will attempt to fetch data.")
            // isProcessing is false at this point. fetchHealthData will set it true.
            // fetchHealthData will also update statusMessage.
            coroutineScope.launch { fetchHealthData(currentContext) }
        } else {
            hasPermissions = false // Explicitly set if not all granted
            Log.d("HealthConnect", "Permissions not all granted. Launching permission request.")
            statusMessage = "Requesting permissions..." // Update status for user
            isProcessing = false // Ensure isProcessing is false before launching dialog
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
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
        if(isProcessing){ // Prevent concurrent send and fetch
             Log.d("SendData", "sendPendingDataToServer called while already processing.")
            return
        }
        isProcessing = true
        statusMessage = "Sending ${healthRecords.size} records to server..."
        var allPostsSuccessful = true

        val recordsToSend = ArrayList(healthRecords) 

        val groupedRecords = recordsToSend.groupBy { it::class }
        for ((recordClass, classRecords) in groupedRecords) {
            if (classRecords.isEmpty()) continue
            val recordTypeSimpleName = recordClass.simpleName ?: "UnknownRecordType"
            val apiUrl = "http://valhall/api/v2/sync/$recordTypeSimpleName"

            val postSuccessful = when (recordClass) {
                HeartRateVariabilityRmssdRecord::class -> handlePostData(HrvRecordSerializable.fromRecordsList(classRecords), HrvRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                WeightRecord::class -> handlePostData(WeightRecordSerializable.fromRecordsList(classRecords), WeightRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
                StepsRecord::class -> handlePostData(StepsRecordSerializable.fromRecordsList(classRecords), StepsRecordSerializable.serializer(), apiUrl, recordTypeSimpleName, ktorHttpClient)
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
                statusMessage = "Failed to send $recordTypeSimpleName. Pending records remain."
                Log.w("SendData", "Post failed for $recordTypeSimpleName.")
                break
            }
        }

        if (allPostsSuccessful) {
            if (pendingTokenToPersist != null) {
                saveChangesToken(currentActiveContext, pendingTokenToPersist)
                statusMessage = "Data sent successfully. Token updated."
                Log.d("SendData", "All posts successful. Saved token: ${pendingTokenToPersist?.take(10)}...")
                pendingTokenToPersist = null
            } else {
                statusMessage = "Data sent successfully, but no new token was pending."
                 Log.d("SendData", "All posts successful. No new token was pending to save.")
            }
            healthRecords = emptyList() 
        } else {
            Log.w("SendData", "Not all posts successful. Pending records and their token candidate remain.")
        }
        isProcessing = false
    }
    
    LaunchedEffect(Unit) { 
        Log.d("HealthConnectScreen", "LaunchedEffect: Initial check - current status: $statusMessage, isProcessing: $isProcessing")
        checkPermissionsAndFetchData(this, context) 
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d("HealthConnectScreen", "App resumed. HasPermissions: $hasPermissions, IsProcessing: $isProcessing")
                if (hasPermissions && !isProcessing) {
                    Log.d("HealthConnectScreen", "Permissions granted and not processing, fetching data on resume.")
                    scope.launch {
                        fetchHealthData(context)
                    }
                } else if (!hasPermissions){
                     Log.d("HealthConnectScreen", "App resumed but no permissions.")
                } else if (isProcessing){
                     Log.d("HealthConnectScreen", "App resumed but already processing.")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val groupedAndSortedRecordsForDisplay by remember(healthRecords) {
        derivedStateOf {
            val zoneId = ZoneId.systemDefault()
            healthRecords
                .groupBy { record -> LocalDateTime.ofInstant(record.getPrimaryInstant(), zoneId).toLocalDate() }
                .entries
                .sortedByDescending { it.key } 
                .map { entry -> entry.key to entry.value.sortedByDescending { record -> record.getPrimaryInstant() } } 
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateHeaderFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d, yyyy") }
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }
    val yesterday = remember { today.minusDays(1) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(statusMessage)
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasPermissions) {
            Button(
                onClick = { scope.launch { checkPermissionsAndFetchData(scope, context) } }, 
                enabled = !isProcessing // isProcessing should be false if we are waiting for permission action
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

        Text("Total pending records: ${healthRecords.size}")
        Spacer(modifier = Modifier.height(8.dp))

        if (groupedAndSortedRecordsForDisplay.isNotEmpty()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                groupedAndSortedRecordsForDisplay.forEach { (date, recordsInGroup) ->
                    item {
                        val dateHeaderText = when (date) {
                            today -> "Today"
                            yesterday -> "Yesterday"
                            else -> date.format(dateHeaderFormatter)
                        }
                        Text(
                            text = dateHeaderText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(recordsInGroup) { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) { 
                            val recordTime = LocalDateTime.ofInstant(record.getPrimaryInstant(), ZoneId.systemDefault()).format(timeFormatter)
                            val recordSummary = getRecordSummary(record)
                            Row {
                                recordTime.forEach { char ->
                                    Text(
                                        text = char.toString(),
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(12.dp) 
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp)) 
                            Text(
                                text = recordSummary,
                                modifier = Modifier.weight(1f) 
                            )
                        }
                    }
                }
            }
        } else if (hasPermissions && !isProcessing) {
            // Status message already covers cases like "No new changes found" or "No records found"
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
