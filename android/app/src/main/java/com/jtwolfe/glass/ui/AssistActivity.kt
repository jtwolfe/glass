package com.jtwolfe.glass.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jtwolfe.glass.R
import com.jtwolfe.glass.ui.theme.GlassTheme

class AssistActivity : ComponentActivity() {

    private var showRoleDialog by mutableStateOf(false)

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            showRoleDialog = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAssistantRole()

        setContent {
            GlassTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ChatScreen()
                    }
                }

                if (showRoleDialog) {
                    AlertDialog(
                        onDismissRequest = { showRoleDialog = false },
                        title = { Text(stringResource(R.string.role_assistant_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.role_assistant_message))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Go to Settings > Apps > Default apps > Digital assistant app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                showRoleDialog = false
                                openAssistantSettings()
                            }) {
                                Text(stringResource(R.string.role_assistant_action))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRoleDialog = false }) {
                                Text("Later")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                roleRequestLauncher.launch(intent)
            } else {
                showRoleDialog = true
            }
        }
    }

    private fun openAssistantSettings() {
        try {
            val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                // Fallback silently
            }
        }
    }
}
