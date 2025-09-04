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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import se.hokasgard.nephelaiapp.ui.theme.NephelaiAppTheme

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

    // Remember a coroutine scope
    val scope = rememberCoroutineScope()

    // 1. Define the set of permissions you want to request
    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    // 2. Create a launcher to request permissions.
    // This launcher handles the result of the permission request.
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        // After the user responds, check if all requested permissions were granted
        if (permissionsMap.values.all { it }) {
            Log.d("HealthConnect", "All permissions granted")
            hasPermissions = true
        } else {
            Log.d("HealthConnect", "Not all permissions were granted")
            hasPermissions = false
        }
    }

    // 3. A function to check permissions and launch the request if needed
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

    // This runs once when the screen is first displayed
    LaunchedEffect(Unit) {
        checkAndRequestPermissions()
    }

    // 4. The UI for your screen
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (hasPermissions) "✅ Permissions Granted for HRV" else "❌ Permissions Not Granted for HRV"
        )
        Button(
            // The button will re-check and ask for permissions if they are missing
            onClick = {
                // We need to launch this in a coroutine scope
                scope.launch { checkAndRequestPermissions() }
            }
        ) {
            Text("Check / Request Permissions")
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