package relay.orchestra.yield

/**
 * Shared public transcript. Append-only; each resident projects a view and
 * never writes these lines into its own [relay.agent.AgentState.messages].
 */
class Scene {
    private val _lines = mutableListOf<Utterance>()
    val lines: List<Utterance> get() = _lines.toList()

    fun append(line: Utterance) {
        _lines += line
    }
}

data class Utterance(
    val speakerId: String,
    val text: String,
    val channel: String = "public",
)

fun interface TurnPolicy {
    suspend fun next(scene: Scene, userJustSpoke: Boolean): String?
}

class Resident(
    val id: String,
    val agent: relay.agent.Agent,
    val project: (Scene) -> String,
)

/** Skip [skip], then walk [ids] once (or [rounds] times). */
class RoundRobin(
    private val ids: List<String>,
    private val skip: Set<String> = setOf("user"),
    private val rounds: Int = 1,
) : TurnPolicy {
    private var issued = 0
    private val queue = ids.filter { it !in skip }

    override suspend fun next(scene: Scene, userJustSpoke: Boolean): String? {
        if (userJustSpoke) issued = 0
        val limit = queue.size * rounds
        if (issued >= limit) return null
        if (queue.isEmpty()) return null
        val id = queue[issued % queue.size]
        issued++
        return id
    }
}
