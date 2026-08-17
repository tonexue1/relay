package relay.werewolf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import relay.orchestra.Cue
import relay.orchestra.yield.Utterance
import relay.werewolf.engine.Channel
import relay.werewolf.engine.Phase
import relay.werewolf.engine.Team
import relay.werewolf.engine.WerewolfEngine
import relay.werewolf.engine.compactTable

class WerewolfEngineTest {

    @Test
    fun publicRosterHidesRolesUntilTheEnd() {
        val engine = WerewolfEngine(compactTable())
        assertFalse(engine.publicRoster().contains("狼人"))
        assertFalse(engine.publicRoster().contains("预言家"))
        assertTrue(engine.publicRoster().contains("一号·在场"))
    }

    @Test
    fun villagerProjectionDropsWolfNight() {
        val engine = WerewolfEngine(compactTable())
        val lines = listOf(
            Utterance("system", "第1夜，天黑请闭眼。", Channel.PUBLIC),
            Utterance("一号", "今晚刀三号", Channel.WOLF),
            Utterance("二号", "查一号", Channel.SEER),
            Utterance("system", "天亮了，三号死了。身份未公布。", Channel.PUBLIC),
            Utterance("四号", "我是民", Channel.PUBLIC),
        )
        val villager = engine.project("四号", lines)
        assertFalse(villager.contains("今晚刀三号"))
        assertFalse(villager.contains("查一号"))
        assertTrue(villager.contains("我是民"))
        assertTrue(villager.contains("村民"))

        val wolf = engine.project("一号", lines)
        assertTrue(wolf.contains("今晚刀三号"))
        assertFalse(wolf.contains("查一号"))

        val seer = engine.project("二号", lines)
        assertTrue(seer.contains("查一号"))
        assertFalse(seer.contains("今晚刀三号"))
    }

    @Test
    fun scriptedDayOneVillageWins() {
        val engine = WerewolfEngine(compactTable())

        narrate(engine)
        speak(engine, "一号", "@三号")
        speak(engine, "二号", "@一号")
        narrate(engine)
        assertFalse(engine.player("三号").alive)
        assertFalse(engine.publicRoster().contains("村民"))

        speak(engine, "一号", "四号很可疑")
        speak(engine, "二号", "一号今晚很安静")
        speak(engine, "四号", "听二号的")
        speak(engine, "一号", "@四号")
        speak(engine, "二号", "@一号")
        speak(engine, "四号", "@一号")
        narrate(engine)
        assertFalse(engine.player("一号").alive)
        narrate(engine)

        assertEquals(Team.VILLAGE, engine.winner)
        assertEquals(Phase.ENDED, engine.phase)
        assertTrue(engine.publicRoster().contains("一号·狼人"))
    }

    private fun speak(engine: WerewolfEngine, id: String, text: String) {
        val cue = engine.next()
        assertTrue(cue is Cue.Speak)
        assertEquals(id, (cue as Cue.Speak).speakerId)
        engine.onSpoken(id, text)
    }

    private fun narrate(engine: WerewolfEngine) {
        assertTrue(engine.next() is Cue.Narrate)
        engine.onNarrated()
    }
}
