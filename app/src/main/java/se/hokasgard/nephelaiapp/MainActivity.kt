package se.hokasgard.nephelaiapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

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

    val scope = rememberCoroutineScope()

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

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
                    ascendingOrder = false // Latest records first
                )
                val response = healthConnectClient.readRecords(request)
                hrvRecords = response.records
                Log.d("HealthConnect", "HRV records read: ${hrvRecords.size}")
            } catch (e: Exception) {
                Log.e("HealthConnect", "Error reading HRV records", e)
            }
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
        Button(
            onClick = {
                scope.launch {
                    checkAndRequestPermissions()
                    // If permissions are granted after click, attempt to read records
                    if (hasPermissions) {
                        readHrvRecords()
                    }
                }
            }
        ) {
            Text(if (hasPermissions) "Refresh HRV Data" else "Check / Request Permissions")
        }

        if (hrvRecords.isNotEmpty()) {
            Text("Latest HRV Records (last 7 days):")
            LazyColumn {
                items(hrvRecords) { record ->
                    Text("Time: ${record.time}, HRV: ${record.heartRateVariabilityMillis} ms")
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
