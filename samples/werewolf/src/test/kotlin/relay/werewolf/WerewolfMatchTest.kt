package relay.werewolf

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.agent.Agent
import relay.agent.AgentConfig
import relay.orchestra.TeamEvent
import relay.werewolf.engine.Channel
import relay.werewolf.engine.Team
import relay.werewolf.engine.WerewolfEngine
import relay.werewolf.engine.compactTable

class WerewolfMatchTest {

    @Test
    fun matchRunsToVillageWinAndVillagerNeverSeesWolfNight() = runTest {
        val wolf = ScriptedProvider(
            listOf(
                { ScriptedProvider.text("刀了\n@三号") },
                { ScriptedProvider.text("四号可疑") },
                { ScriptedProvider.text("@四号") },
            ),
        )
        val seer = ScriptedProvider(
            listOf(
                { ScriptedProvider.text("查一下\n@一号") },
                { ScriptedProvider.text("一号今晚很安静") },
                { ScriptedProvider.text("@一号") },
            ),
        )
        val villager = ScriptedProvider(
            listOf(
                { ScriptedProvider.text("听二号的") },
                { ScriptedProvider.text("@一号") },
            ),
        )
        val silent = ScriptedProvider(emptyList())
        val providers = mapOf("一号" to wolf, "二号" to seer, "四号" to villager)
        val match = WerewolfMatch(WerewolfEngine(compactTable())) { id, transform ->
            Agent(
                provider = providers[id] ?: silent,
                config = AgentConfig(model = "fake-model", maxTurns = 1),
                transformContext = transform,
            )
        }

        val events = match.play().toList()
        assertEquals(Team.VILLAGE, match.engine.winner)
        assertTrue(events.filterIsInstance<TeamEvent.Utterance>().any { it.text.contains("好人阵营") })

        val publicText = events.filterIsInstance<TeamEvent.Utterance>()
            .filter { it.channel == Channel.PUBLIC }
            .joinToString { it.text }
        assertTrue(!publicText.contains("刀了"))

        val villagerRequest = villager.receivedRequests.joinToString("\n") { req ->
            req.messages.joinToString { it.content.orEmpty() }
        }
        assertTrue(!villagerRequest.contains("刀了"))
        assertTrue(wolf.receivedRequests.first().messages.any { it.content?.contains("狼人") == true })
    }
}
