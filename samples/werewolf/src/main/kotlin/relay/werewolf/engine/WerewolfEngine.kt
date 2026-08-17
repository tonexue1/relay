package relay.werewolf.engine

import kotlin.random.Random
import relay.orchestra.Cue
import relay.orchestra.DirectorPolicy

enum class Role { WOLF, SEER, VILLAGER }

enum class Team { WOLVES, VILLAGE }

enum class Phase {
    NIGHT_WOLVES,
    NIGHT_SEER,
    DAWN,
    DAY_TALK,
    DAY_VOTE,
    ENDED,
}

data class Player(
    val id: String,
    val role: Role,
    var alive: Boolean = true,
)

data class SeerCheck(
    val targetId: String,
    val role: Role,
)

object Channel {
    const val PUBLIC = "public"
    const val WOLF = "wolf"
    const val SEER = "seer"
}

class WerewolfEngine(
    val players: List<Player>,
) : DirectorPolicy {
    init {
        require(players.size >= 4) { "Need at least 4 players" }
        require(players.map { it.id }.toSet().size == players.size) { "Player ids must be unique" }
        require(players.any { it.role == Role.WOLF }) { "Need at least one wolf" }
    }

    var day: Int = 1
        private set
    var phase: Phase = Phase.NIGHT_WOLVES
        private set
    var winner: Team? = null
        private set
    var lastDeath: String? = null
        private set
    val seerChecks: List<SeerCheck> get() = _seerChecks.toList()

    private val _seerChecks = mutableListOf<SeerCheck>()
    private var wolfSpeakIdx = 0
    private var wolfTarget: String? = null
    private var seerActed = false
    private var nightIntroduced = false
    private var dawnAnnounced = false
    private var talkIdx = 0
    private var voteIdx = 0
    private var voteAnnounced = false
    private val votes = linkedMapOf<String, String>()

    fun alive(): List<Player> = players.filter { it.alive }

    fun player(id: String): Player = players.first { it.id == id }

    override fun next(): Cue? {
        if (phase == Phase.ENDED) return null
        while (true) {
            when (phase) {
                Phase.NIGHT_WOLVES -> {
                    if (!nightIntroduced) {
                        nightIntroduced = true
                        return Cue.Narrate("第${day}夜，天黑请闭眼。")
                    }
                    val speaker = alive().filter { it.role == Role.WOLF }.getOrNull(wolfSpeakIdx)
                    if (speaker != null) {
                        return Cue.Speak(speaker.id, wolfTick(speaker.id), Channel.WOLF)
                    }
                    resolveWolfKill()
                    phase = Phase.NIGHT_SEER
                    seerActed = false
                }
                Phase.NIGHT_SEER -> {
                    val seer = alive().firstOrNull { it.role == Role.SEER }
                    if (seer != null && !seerActed) {
                        return Cue.Speak(seer.id, seerTick(seer.id), Channel.SEER)
                    }
                    phase = Phase.DAWN
                    dawnAnnounced = false
                }
                Phase.DAWN -> {
                    if (!dawnAnnounced) {
                        dawnAnnounced = true
                        return Cue.Narrate(dawnText())
                    }
                    if (checkWin()) {
                        phase = Phase.ENDED
                        return Cue.Narrate(winText())
                    }
                    phase = Phase.DAY_TALK
                    talkIdx = 0
                }
                Phase.DAY_TALK -> {
                    val speaker = alive().getOrNull(talkIdx)
                    if (speaker != null) {
                        return Cue.Speak(speaker.id, talkTick(speaker.id), Channel.PUBLIC)
                    }
                    phase = Phase.DAY_VOTE
                    voteIdx = 0
                    voteAnnounced = false
                    votes.clear()
                }
                Phase.DAY_VOTE -> {
                    val speaker = alive().getOrNull(voteIdx)
                    if (speaker != null) {
                        return Cue.Speak(speaker.id, voteTick(speaker.id), Channel.PUBLIC)
                    }
                    if (!voteAnnounced) {
                        voteAnnounced = true
                        return Cue.Narrate(resolveVotes())
                    }
                    if (checkWin()) {
                        phase = Phase.ENDED
                        return Cue.Narrate(winText())
                    }
                    day++
                    resetNight()
                    phase = Phase.NIGHT_WOLVES
                }
                Phase.ENDED -> return null
            }
        }
    }

    override fun onSpoken(speakerId: String, text: String) {
        val target = parseTarget(text, except = speakerId)
        when (phase) {
            Phase.NIGHT_WOLVES -> {
                if (target != null && player(target).role != Role.WOLF && player(target).alive) {
                    wolfTarget = target
                }
                wolfSpeakIdx++
            }
            Phase.NIGHT_SEER -> {
                if (target != null && player(target).alive) {
                    _seerChecks += SeerCheck(target, player(target).role)
                }
                seerActed = true
            }
            Phase.DAY_TALK -> talkIdx++
            Phase.DAY_VOTE -> {
                votes[speakerId] = target ?: fallbackVote(speakerId)
                voteIdx++
            }
            else -> Unit
        }
    }

    override fun onNarrated() {
        // Flags are set before the narrate step is returned.
    }

    fun visibleLines(viewerId: String, lines: List<relay.orchestra.yield.Utterance>): List<relay.orchestra.yield.Utterance> {
        val viewer = player(viewerId)
        return lines.filter { line ->
            when (line.channel) {
                Channel.WOLF -> viewer.role == Role.WOLF
                Channel.SEER -> viewer.role == Role.SEER
                else -> true
            }
        }
    }

    fun project(viewerId: String, lines: List<relay.orchestra.yield.Utterance>): String {
        val viewer = player(viewerId)
        val visible = visibleLines(viewerId, lines)
        return buildString {
            append("你是${viewer.id}，身份：${roleName(viewer.role)}。")
            append(if (viewer.alive) "你还活着。" else "你已经出局。")
            append("存活：${alive().joinToString("、") { it.id }}。")
            if (viewer.role == Role.WOLF) {
                val pack = players.filter { it.role == Role.WOLF }.joinToString("、") { it.id }
                append("狼同伴：$pack。")
            }
            if (viewer.role == Role.SEER && _seerChecks.isNotEmpty()) {
                append("你的查验：")
                append(_seerChecks.joinToString("；") { "${it.targetId}是${roleName(it.role)}" })
                append("。")
            }
            append("当前阶段：${phaseLabel()}。")
            if (visible.isNotEmpty()) {
                append('\n')
                append(visible.joinToString("\n") { "${it.speakerId}: ${it.text}" })
            }
        }
    }

    private fun resolveWolfKill() {
        val targetId = wolfTarget
            ?: alive().firstOrNull { it.role != Role.WOLF }?.id
        lastDeath = null
        if (targetId != null) {
            player(targetId).alive = false
            lastDeath = targetId
        }
    }

    private fun resolveVotes(): String {
        if (votes.isEmpty()) return "白天没有有效票，无人出局。"
        val tally = votes.values.groupingBy { it }.eachCount()
        val top = tally.maxBy { it.value }
        val tied = tally.filter { it.value == top.value }.keys
        if (tied.size > 1) return "平票（${tied.joinToString("、")}），无人出局。"
        val out = top.key
        player(out).alive = false
        lastDeath = out
        return "${out}被放逐。"
    }

    private fun checkWin(): Boolean {
        val wolves = alive().count { it.role == Role.WOLF }
        val village = alive().count { it.role != Role.WOLF }
        winner = when {
            wolves == 0 -> Team.VILLAGE
            wolves >= village -> Team.WOLVES
            else -> null
        }
        return winner != null
    }

    private fun dawnText(): String {
        val dead = lastDeath
        return if (dead == null) "天亮了，昨晚是平安夜。" else "天亮了，${dead}死了。身份未公布。"
    }

    private fun winText(): String {
        val result = when (winner) {
            Team.VILLAGE -> "游戏结束，好人阵营胜利。"
            Team.WOLVES -> "游戏结束，狼人胜利。"
            null -> "游戏结束。"
        }
        val reveal = players.joinToString("、") { "${it.id}是${roleName(it.role)}" }
        return "$result 身份公布：$reveal。"
    }

    fun publicPhaseLabel(): String = when (phase) {
        Phase.NIGHT_WOLVES, Phase.NIGHT_SEER -> "天黑"
        Phase.DAWN -> "天亮"
        Phase.DAY_TALK -> "发言"
        Phase.DAY_VOTE -> "投票"
        Phase.ENDED -> "结束"
    }

    fun publicRoster(revealRoles: Boolean = phase == Phase.ENDED): String =
        players.joinToString("  ") { p ->
            val life = if (p.alive) "在场" else "出局"
            if (revealRoles) "${p.id}·${roleName(p.role)}·$life" else "${p.id}·$life"
        }

    private fun resetNight() {
        wolfSpeakIdx = 0
        wolfTarget = null
        seerActed = false
        nightIntroduced = false
        dawnAnnounced = false
        voteAnnounced = false
        lastDeath = null
    }

    private fun parseTarget(text: String, except: String): String? {
        val roster = players.map { it.id }.filter { it != except }
        var i = 0
        while (i < text.length) {
            val at = text.indexOf('@', i)
            if (at < 0) break
            val hit = roster.firstOrNull { id ->
                text.startsWith(id, at + 1) &&
                    (at + 1 + id.length >= text.length || !text[at + 1 + id.length].isLetterOrDigit())
            }
            if (hit != null && player(hit).alive) return hit
            i = at + 1
        }
        return null
    }

    private fun fallbackVote(voterId: String): String =
        alive().first { it.id != voterId }.id

    private fun wolfTick(id: String): String {
        val prey = alive().filter { it.role != Role.WOLF }.joinToString("、") { "@${it.id}" }
        return "你是${id}，暗牌是狼人。场上只有座位号，不要在白天承认。今晚刀谁？写 $prey 其中一个。"
    }

    private fun seerTick(id: String): String {
        val others = alive().filter { it.id != id }.joinToString("、") { "@${it.id}" }
        return "你是${id}，暗牌是预言家。查验只有你知道。今晚查谁？写 $others 其中一个。"
    }

    private fun talkTick(id: String): String =
        "你是${id}。场上只显示座位号，没有官方身份。白天发言一两句，可以指控或装傻。不要扮演主持人。"

    private fun voteTick(id: String): String {
        val others = alive().filter { it.id != id }.joinToString("、") { "@${it.id}" }
        return "你是${id}，投票放逐一个座位。写 $others 其中一个。不要公布别人的官方身份。"
    }

    private fun phaseLabel(): String = when (phase) {
        Phase.NIGHT_WOLVES -> "狼人夜谈"
        Phase.NIGHT_SEER -> "预言家查验"
        Phase.DAWN -> "天亮"
        Phase.DAY_TALK -> "白天发言"
        Phase.DAY_VOTE -> "白天投票"
        Phase.ENDED -> "结束"
    }
}

fun roleName(role: Role): String = when (role) {
    Role.WOLF -> "狼人"
    Role.SEER -> "预言家"
    Role.VILLAGER -> "村民"
}

val SEAT_NAMES: List<String> = listOf("一号", "二号", "三号", "四号", "五号", "六号")

fun deal(
    seats: List<String> = SEAT_NAMES,
    wolves: Int = 2,
    seers: Int = 1,
    random: Random = Random.Default,
): List<Player> {
    require(wolves >= 1 && seers >= 0 && wolves + seers < seats.size)
    val roles = buildList {
        repeat(wolves) { add(Role.WOLF) }
        repeat(seers) { add(Role.SEER) }
        repeat(seats.size - wolves - seers) { add(Role.VILLAGER) }
    }.shuffled(random)
    return seats.zip(roles) { id, role -> Player(id, role) }
}

/** Fixed deal for tests: 一号狼、二号预言家、三号/四号民。 */
fun compactTable(): List<Player> = listOf(
    Player("一号", Role.WOLF),
    Player("二号", Role.SEER),
    Player("三号", Role.VILLAGER),
    Player("四号", Role.VILLAGER),
)
