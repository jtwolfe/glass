package com.jtwolfe.glass.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsTest {

    private val cachedAgentsKey = stringPreferencesKey("cached_agents_json")
    private val selectedAgentIdKey = stringPreferencesKey("selected_agent_id")
    private val selectedAgentNameKey = stringPreferencesKey("selected_agent_name")

    @Test
    fun emptySuccessPersistsEmptyJsonAndDeletesSelectionKeys() = runBlocking {
        val store = MemoryPrefsStore()
        val settings = AgentSettings(store)
        settings.updateAvailableAgents(listOf(Agent("a", "Ada")))
        assertEquals("a", settings.getSelectedAgentId())

        settings.updateAvailableAgents(emptyList())

        val prefs = store.data.first()
        assertEquals("[]", prefs[cachedAgentsKey])
        assertNull(prefs[selectedAgentIdKey])
        assertNull(prefs[selectedAgentNameKey])
        assertNull(settings.getSelectedAgentId())
        assertEquals(AgentSettings.PLACEHOLDER_AGENT, settings.selectedAgent.first())
        assertTrue(settings.roster.value.agents.isEmpty())
        assertFalse(settings.roster.value.stale)
        assertNull(settings.roster.value.lastError)
    }

    @Test
    fun failedFetchKeepsCacheAndSelection() = runBlocking {
        val store = MemoryPrefsStore()
        val settings = AgentSettings(store)
        val agents = listOf(Agent("a", "Ada"), Agent("c", "Bea"))
        settings.updateAvailableAgents(agents)
        settings.setSelectedAgent(Agent("c", "Bea"))
        val jsonBefore = store.data.first()[cachedAgentsKey]

        settings.markAgentsFetchFailed("agent_unavailable")

        assertEquals(agents, settings.roster.value.agents)
        assertTrue(settings.roster.value.stale)
        assertEquals("agent_unavailable", settings.roster.value.lastError)
        assertEquals("c", settings.getSelectedAgentId())
        assertEquals(jsonBefore, store.data.first()[cachedAgentsKey])
        assertEquals("c", store.data.first()[selectedAgentIdKey])
        assertEquals("Bea", store.data.first()[selectedAgentNameKey])
    }

    @Test
    fun processRestartFromCachedEmptyStaysEmpty() = runBlocking {
        val store = MemoryPrefsStore()
        val first = AgentSettings(store)
        first.updateAvailableAgents(listOf(Agent("a", "Ada")))
        first.updateAvailableAgents(emptyList())
        assertEquals("[]", store.data.first()[cachedAgentsKey])

        val restarted = AgentSettings(store)
        restarted.loadCachedAgents()

        assertTrue(restarted.roster.value.agents.isEmpty())
        assertFalse(restarted.roster.value.stale)
        assertNull(restarted.roster.value.lastError)
        assertEquals(AgentSettings.PLACEHOLDER_AGENT, restarted.selectedAgent.first())
    }

    @Test
    fun loadCachedAgentsAppliesPersistedEmptyOverInMemoryList() = runBlocking {
        val store = MemoryPrefsStore()
        val settings = AgentSettings(store)
        settings.updateAvailableAgents(listOf(Agent("a", "Ada")))
        store.edit { prefs ->
            prefs[cachedAgentsKey] = "[]"
            prefs.remove(selectedAgentIdKey)
            prefs.remove(selectedAgentNameKey)
        }
        assertEquals(1, settings.roster.value.agents.size)

        settings.loadCachedAgents()

        assertTrue(settings.roster.value.agents.isEmpty())
    }

    @Test
    fun updatePicksLastAgentIdWhenCurrentMissing() = runBlocking {
        val settings = AgentSettings(MemoryPrefsStore())
        settings.updateAvailableAgents(
            listOf(Agent("a", "Ada"), Agent("c", "Bea")),
            lastAgentId = "c",
        )
        assertEquals("c", settings.getSelectedAgentId())
        assertEquals("Bea", settings.selectedAgent.first().name)
    }

    @Test
    fun updateKeepsCurrentWhenStillPresent() = runBlocking {
        val settings = AgentSettings(MemoryPrefsStore())
        settings.updateAvailableAgents(listOf(Agent("a", "Ada")))
        settings.updateAvailableAgents(
            listOf(Agent("a", "Ada"), Agent("c", "Bea")),
            lastAgentId = "c",
        )
        assertEquals("a", settings.getSelectedAgentId())
    }

    @Test
    fun updateFallsBackToFirstWhenLastAgentIdMissing() = runBlocking {
        val settings = AgentSettings(MemoryPrefsStore())
        settings.updateAvailableAgents(
            listOf(Agent("a", "Ada"), Agent("c", "Bea")),
            lastAgentId = "missing",
        )
        assertEquals("a", settings.getSelectedAgentId())
    }

    @Test
    fun setSelectedAgentIgnoresBlankId() = runBlocking {
        val settings = AgentSettings(MemoryPrefsStore())
        settings.updateAvailableAgents(listOf(Agent("a", "Ada")))
        settings.setSelectedAgent(AgentSettings.PLACEHOLDER_AGENT)
        assertEquals("a", settings.getSelectedAgentId())
    }

    private class MemoryPrefsStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            val next = transform(state.value)
            state.value = next
            next
        }
    }
}
