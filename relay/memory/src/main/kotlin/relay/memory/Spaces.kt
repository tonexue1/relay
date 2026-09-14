package relay.memory

import relay.memory.api.ClockDomain
import relay.memory.api.MemoryRuntime
import relay.memory.api.StateSchemaSnapshot

suspend fun MemoryRuntime.ensureSpace(
    spaceId: String,
    domain: ClockDomain = ClockDomain.WALL_CLOCK,
) {
    registerStateSchema(StateSchemaSnapshot(spaceId = spaceId, clockDomain = domain))
}
