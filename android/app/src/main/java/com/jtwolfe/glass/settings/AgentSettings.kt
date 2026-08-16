package com.jtwolfe.glass.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.agentStore by preferencesDataStore(name = "glass_agent_settings")

data class Agent(
    val id: String,
    val name: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)

    companion object {
        fun fromJson(json: JSONObject): Agent? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            return Agent(id, name)
        }
    }
}

class AgentSettings(private val context: Context) {

    private val selectedAgentIdKey = stringPreferencesKey("selected_agent_id")
    private val selectedAgentNameKey = stringPreferencesKey("selected_agent_name")
    private val cachedAgentsKey = stringPreferencesKey("cached_agents_json")

    private val _availableAgents = MutableStateFlow<List<Agent>>(emptyList())
    val availableAgents = _availableAgents.asStateFlow()

    val selectedAgent: Flow<Agent> = context.agentStore.data.map { prefs ->
        val id = prefs[selectedAgentIdKey]
        val name = prefs[selectedAgentNameKey]
        if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
            Agent(id, name)
        } else {
            PLACEHOLDER_AGENT
        }
    }

    suspend fun getSelectedAgentId(): String? {
        return context.agentStore.data.map { prefs ->
            prefs[selectedAgentIdKey]?.takeIf { it.isNotBlank() }
        }.first()
    }

    suspend fun setSelectedAgent(agent: Agent) {
        if (agent.id.isBlank()) return
        context.agentStore.edit { prefs ->
            prefs[selectedAgentIdKey] = agent.id
            prefs[selectedAgentNameKey] = agent.name
        }
    }

    suspend fun updateAvailableAgents(agents: List<Agent>) {
        if (agents.isEmpty()) return

        _availableAgents.value = agents

        val json = JSONArray().apply {
            agents.forEach { put(it.toJson()) }
        }.toString()

        context.agentStore.edit { prefs ->
            prefs[cachedAgentsKey] = json
        }

        val currentId = getSelectedAgentId()
        val stillExists = currentId != null && agents.any { it.id == currentId }
        if (!stillExists) {
            setSelectedAgent(agents.first())
        }
    }

    suspend fun loadCachedAgents() {
        val json = context.agentStore.data.map { prefs ->
            prefs[cachedAgentsKey]
        }.first()

        if (json != null) {
            val agents = parseAgentsJson(json)
            if (agents.isNotEmpty()) {
                _availableAgents.value = agents
            }
        }
    }

    private fun parseAgentsJson(json: String): List<Agent> {
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    Agent.fromJson(array.getJSONObject(i))?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val PLACEHOLDER_NAME = "Glass"
        val PLACEHOLDER_AGENT = Agent(id = "", name = PLACEHOLDER_NAME)
    }
}
