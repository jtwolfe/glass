package com.jtwolfe.glass

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jtwolfe.glass.chat.ChatViewModel
import com.jtwolfe.glass.ui.GlassRoot
import com.jtwolfe.glass.ui.theme.GlassTheme

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels { ChatViewModel.factory(application) }

    private var isAssistant by mutableStateOf(false)

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshAssistantRole()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshAssistantRole()
        setContent {
            GlassTheme {
                GlassRoot(
                    viewModel = chatViewModel,
                    isDefaultAssistant = isAssistant,
                    onRequestAssistantRole = ::requestAssistantRole,
                    onOpenAssistantSettings = ::openAssistantSettingsFallback,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatViewModel.onAssistOpened()
    }

    override fun onResume() {
        super.onResume()
        refreshAssistantRole()
        chatViewModel.refresh()
    }

    private fun refreshAssistantRole() {
        isAssistant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            roles != null &&
                roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            false
        }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                try {
                    roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    return
                } catch (_: Exception) {
                    // fall through to Settings
                }
            }
        }
        openAssistantSettingsFallback()
    }

    private fun openAssistantSettingsFallback() {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
    }
}
