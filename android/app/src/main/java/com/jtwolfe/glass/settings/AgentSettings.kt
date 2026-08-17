package com.jtwolfe.glass.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

data class AgentRosterState(
    val agents: List<Agent>,
    val stale: Boolean = false,
    val lastError: String? = null, // null = last fetch ok (possibly empty)
)

class AgentSettings(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.agentStore)

    private val selectedAgentIdKey = stringPreferencesKey("selected_agent_id")
    private val selectedAgentNameKey = stringPreferencesKey("selected_agent_name")
    private val cachedAgentsKey = stringPreferencesKey("cached_agents_json")

    private val _roster = MutableStateFlow(AgentRosterState(emptyList()))
    val roster = _roster.asStateFlow()
    val availableAgents: List<Agent> get() = _roster.value.agents

    val selectedAgent: Flow<Agent> = store.data.map { prefs ->
        val id = prefs[selectedAgentIdKey]
        val name = prefs[selectedAgentNameKey]
        if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
            Agent(id, name)
        } else {
            PLACEHOLDER_AGENT
        }
    }

    suspend fun getSelectedAgentId(): String? {
        return store.data.map { prefs ->
            prefs[selectedAgentIdKey]?.takeIf { it.isNotBlank() }
        }.first()
    }

    suspend fun setSelectedAgent(agent: Agent) {
        if (agent.id.isBlank()) return
        store.edit { prefs ->
            prefs[selectedAgentIdKey] = agent.id
            prefs[selectedAgentNameKey] = agent.name
        }
    }

    suspend fun updateAvailableAgents(
        agents: List<Agent>,
        stale: Boolean = false,
        lastAgentId: String? = null,
    ) {
        _roster.value = AgentRosterState(agents, stale = stale, lastError = null)
        val json = JSONArray().apply {
            agents.forEach { put(it.toJson()) }
        }.toString()
        store.edit { prefs ->
            prefs[cachedAgentsKey] = json
            if (agents.isEmpty()) {
                prefs.remove(selectedAgentIdKey)
                prefs.remove(selectedAgentNameKey)
            } else {
                val currentId = prefs[selectedAgentIdKey]?.takeIf { it.isNotBlank() }
                val still = currentId != null && agents.any { it.id == currentId }
                if (!still) {
                    val pick = lastAgentId?.let { id -> agents.find { it.id == id } }
                        ?: agents.first()
                    prefs[selectedAgentIdKey] = pick.id
                    prefs[selectedAgentNameKey] = pick.name
                }
            }
        }
    }

    suspend fun loadCachedAgents() {
        val json = store.data.map { prefs ->
            prefs[cachedAgentsKey]
        }.first()

        if (json != null) {
            val agents = parseAgentsJson(json)
            _roster.value = AgentRosterState(agents, stale = false, lastError = null)
        }
    }

    fun markAgentsFetchFailed(message: String) {
        _roster.value = _roster.value.copy(stale = true, lastError = message)
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
