package relay.memory.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import relay.memory.MemoryScope
import relay.memory.engine.SqliteLedgerRuntime
import relay.memory.testContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerRuntimeContractTest {

    private fun payload(value: String) = JsonObject(mapOf("value" to JsonPrimitive(value)))

    private fun source(id: String) = SourceRef(SourceType.RAW_EVENT, id)

    private fun field(
        space: String,
        id: String,
        policy: OverwritePolicy = OverwritePolicy.EXTRACTOR_CAN_CURRENT,
        writers: Set<MemoryWriterKind> = MemoryWriterKind.entries.toSet(),
    ) = StateFieldSpec(spaceId = space, fieldId = id, overwritePolicy = policy, allowedWriters = writers)

    private suspend fun MemoryRuntime.seedAssistant(): MemoryRuntime {
        registerStateSchema(
            StateSchemaSnapshot(
                spaceId = "assistant",
                clockDomain = ClockDomain.WALL_CLOCK,
                fields = listOf(
                    field("assistant", "allergies", OverwritePolicy.USER_LOCK),
                    field("assistant", "location"),
                ),
            ),
        )
        return this
    }

    private suspend fun MemoryRuntime.seedNovel(): MemoryRuntime {
        registerStateSchema(
            StateSchemaSnapshot(
                spaceId = "novel:linwan",
                clockDomain = ClockDomain.STORY_TIME,
                fields = listOf(
                    field("novel:linwan", "location"),
                    field("novel:linwan", "current_goal"),
                    field("novel:linwan", "affiliation", OverwritePolicy.USER_LOCK),
                ),
            ),
        )
        return this
    }

    private fun runtime() = SqliteLedgerRuntime(testContext())

    private suspend fun MemoryRuntime.captureUser(
        space: String,
        owner: String,
        text: String,
        domain: ClockDomain,
        sessionId: String = "s1",
    ): String = capture(
        RawEventDraft(
            spaceId = space,
            ownerId = owner,
            role = "user",
            content = text,
            clockDomain = domain,
            sessionId = sessionId,
        ),
    )

    private fun stateBatch(
        space: String,
        owner: String,
        writer: MemoryWriterKind,
        cmd: StateCommand,
        rawIds: List<String> = cmd.sources.map { it.id },
        runId: String = "run-1",
    ) = MemoryBatch(
        spaceId = space,
        ownerId = owner,
        writerKind = writer,
        writerId = writer.name.lowercase(),
        writerRunId = runId,
        commands = listOf(cmd),
        commitRawIds = rawIds,
    )

    @Test
    fun wrongClockDomainIsRejected() = runTest {
        val mem = runtime().seedNovel()
        val fault = assertFailsWith<MemoryFault> {
            mem.captureUser("novel:linwan", "林晚", "码头", ClockDomain.WALL_CLOCK)
        }
        assertEquals(MemoryCodes.CLOCK_DOMAIN_MISMATCH, fault.code)

        val raw = mem.captureUser("novel:linwan", "林晚", "码头", ClockDomain.STORY_TIME)
        val result = mem.commit(
            stateBatch(
                "novel:linwan",
                "林晚",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "location",
                    payload = payload("码头"),
                    rendered = RenderedText("码头"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                ),
            ),
        )
        assertFalse(result.ok)
        assertEquals(MemoryCodes.CLOCK_DOMAIN_MISMATCH, result.error?.code)
    }

    @Test
    fun unknownFieldCannotBeReadOrWritten() = runTest {
        val mem = runtime().seedAssistant()
        val raw = mem.captureUser("assistant", "user", "巴拉巴拉", ClockDomain.WALL_CLOCK)
        val written = mem.commit(
            stateBatch(
                "assistant",
                "user",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "巴拉巴拉",
                    payload = payload("x"),
                    rendered = RenderedText("x"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                ),
            ),
        )
        assertEquals(MemoryCodes.UNKNOWN_FIELD, written.error?.code)

        val read = mem.getStates(
            StateReadRequest(
                spaceId = "assistant",
                ownerId = "user",
                at = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                selectors = setOf(StateSelector("巴拉巴拉")),
            ),
        )
        assertEquals(MemoryCodes.UNKNOWN_FIELD, read.errors["巴拉巴拉"]?.code)
    }

    @Test
    fun ensureThenAliasAndCollision() = runTest {
        val mem = runtime().seedAssistant()
        val created = mem.ensureStateField(field("assistant", "巴拉巴拉"))
        assertTrue(created.created)
        assertEquals("巴拉巴拉", created.fieldId)
        val reused = mem.ensureStateField(field("assistant", "巴拉巴拉"))
        assertFalse(reused.created)

        mem.putFieldAlias("assistant", "过敏", "allergies")
        val viaAlias = mem.ensureStateField(field("assistant", "过敏"))
        assertEquals("allergies", viaAlias.fieldId)
        assertFalse(viaAlias.created)

        val collide = assertFailsWith<MemoryFault> {
            mem.putFieldAlias("assistant", "location", "allergies")
        }
        assertEquals(MemoryCodes.ALIAS_COLLISION, collide.code)
    }

    @Test
    fun oneCurrentValueAndNovelClocksAndIdempotency() = runTest {
        val mem = runtime().seedNovel()
        val raw = mem.captureUser("novel:linwan", "林晚", "在码头", ClockDomain.STORY_TIME)
        val first = mem.commit(
            stateBatch(
                "novel:linwan",
                "林晚",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "location",
                    payload = payload("码头"),
                    rendered = RenderedText("码头"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.STORY_TIME, 1),
                ),
            ),
        )
        assertTrue(first.ok)

        val missing = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.EXTRACTOR,
                writerId = "ex",
                writerRunId = "run-ep",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "ch1.dead",
                        occurredAt = null,
                        rendered = RenderedText("王二已死"),
                        sources = listOf(source(raw)),
                    ),
                ),
                commitRawIds = listOf(raw),
            ),
        )
        assertEquals(MemoryCodes.MISSING_CLOCK, missing.error?.code)

        val ep1 = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.EXTRACTOR,
                writerId = "ex",
                writerRunId = "run-ep1",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "ch30.temple.meeting",
                        occurredAt = ClockStamp(ClockDomain.STORY_TIME, 30),
                        rendered = RenderedText("庙外遇赵"),
                        sources = listOf(source(raw)),
                    ),
                ),
            ),
        )
        assertTrue(ep1.ok)
        val replay = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.EXTRACTOR,
                writerId = "ex",
                writerRunId = "run-ep1b",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "ch30.temple.meeting",
                        occurredAt = ClockStamp(ClockDomain.STORY_TIME, 30),
                        rendered = RenderedText("庙外遇赵"),
                        sources = listOf(source(raw)),
                    ),
                ),
            ),
        )
        assertEquals(MemoryCodes.IDEMPOTENT_REPLAY, replay.error?.code)

        val zhaoRaw = mem.captureUser("novel:linwan", "赵捕头", "庙外", ClockDomain.STORY_TIME)
        val epZhao = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "赵捕头",
                writerKind = MemoryWriterKind.EXTRACTOR,
                writerId = "ex",
                writerRunId = "run-zhao",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "ch30.temple.meeting",
                        occurredAt = ClockStamp(ClockDomain.STORY_TIME, 30),
                        rendered = RenderedText("庙外遇林晚"),
                        sources = listOf(source(zhaoRaw)),
                    ),
                ),
            ),
        )
        assertTrue(epZhao.ok)

        val second = mem.commit(
            stateBatch(
                "novel:linwan",
                "林晚",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "location",
                    payload = payload("西山庙"),
                    rendered = RenderedText("西山庙"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.STORY_TIME, 30),
                ),
                runId = "run-2",
            ),
        )
        assertTrue(second.ok)
        val history = mem.getStateHistory(
            StateHistoryRequest("novel:linwan", "林晚", "location"),
        )
        assertEquals(1, history.count { it.isCurrent })
        assertEquals("西山庙", history.single { it.isCurrent }.text)
        assertEquals(2, history.size)
    }

    @Test
    fun asOfStateHidesFutureAndMissingBlocks() = runTest {
        val mem = runtime().seedNovel()
        val raw = mem.captureUser("novel:linwan", "林晚", "走位", ClockDomain.STORY_TIME)
        assertTrue(
            mem.commit(
                stateBatch(
                    "novel:linwan",
                    "林晚",
                    MemoryWriterKind.EXTRACTOR,
                    StateCommand(
                        fieldId = "location",
                        payload = payload("西山庙"),
                        rendered = RenderedText("西山庙"),
                        sources = listOf(source(raw)),
                        validFrom = ClockStamp(ClockDomain.STORY_TIME, 30),
                    ),
                ),
            ).ok,
        )
        assertTrue(
            mem.commit(
                stateBatch(
                    "novel:linwan",
                    "林晚",
                    MemoryWriterKind.EXTRACTOR,
                    StateCommand(
                        fieldId = "location",
                        payload = payload("县衙大堂"),
                        rendered = RenderedText("县衙大堂"),
                        sources = listOf(source(raw)),
                        validFrom = ClockStamp(ClockDomain.STORY_TIME, 80),
                    ),
                    runId = "run-80",
                ),
            ).ok,
        )

        val at30 = mem.getStates(
            StateReadRequest(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                at = ClockStamp(ClockDomain.STORY_TIME, 30),
                selectors = setOf(StateSelector("location"), StateSelector("current_goal")),
            ),
        )
        assertEquals("西山庙", at30.states["location"]?.text)
        assertFalse(at30.states.containsKey("current_goal"))

        val recall30 = mem.recall(
            RecallRequest(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                query = "大堂",
                at = ClockStamp(ClockDomain.STORY_TIME, 30),
                requiredFields = listOf(RequiredField("location"), RequiredField("current_goal")),
                contextContractId = "novel",
                contextContractVersion = "1",
            ),
        )
        assertEquals(RecallStatus.BLOCKED, recall30.status)
        assertEquals("西山庙", recall30.required["location"]?.text)
        assertTrue(recall30.hits.none { it.text.contains("县衙大堂") })

        val at80 = mem.getStates(
            StateReadRequest(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                at = ClockStamp(ClockDomain.STORY_TIME, 80),
                selectors = setOf(StateSelector("location")),
            ),
        )
        assertEquals("县衙大堂", at80.states["location"]?.text)
    }

    @Test
    fun searchHonorsOwnerSessionAndClosedValidTo() = runTest {
        val mem = runtime().seedAssistant()
        val a = mem.captureUser("assistant", "user", "我住杭州", ClockDomain.WALL_CLOCK, sessionId = "old")
        assertTrue(
            mem.commit(
                stateBatch(
                    "assistant",
                    "user",
                    MemoryWriterKind.EXTRACTOR,
                    StateCommand(
                        fieldId = "location",
                        payload = payload("杭州"),
                        rendered = RenderedText("杭州"),
                        sources = listOf(source(a)),
                        validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                    ),
                ),
            ).ok,
        )
        assertTrue(
            mem.commit(
                stateBatch(
                    "assistant",
                    "user",
                    MemoryWriterKind.EXTRACTOR,
                    StateCommand(
                        fieldId = "location",
                        payload = payload("上海"),
                        rendered = RenderedText("上海"),
                        sources = listOf(source(a)),
                        validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 10),
                    ),
                    runId = "run-sh",
                ),
            ).ok,
        )
        val plan = mem.captureUser("assistant", "user", "今晚打算火锅", ClockDomain.WALL_CLOCK, sessionId = "old")
        assertTrue(
            mem.commit(
                MemoryBatch(
                    spaceId = "assistant",
                    ownerId = "user",
                    writerKind = MemoryWriterKind.EXTRACTOR,
                    writerId = "ex",
                    writerRunId = "run-ses",
                    commands = listOf(
                        EpisodeCommand(
                            idempotencyKey = "tonight.hotpot",
                            occurredAt = ClockStamp(ClockDomain.WALL_CLOCK, 5),
                            rendered = RenderedText("今晚打算火锅"),
                            sources = listOf(source(plan)),
                            scope = MemoryScope.SESSION,
                            scopeId = "old",
                        ),
                    ),
                    commitRawIds = listOf(plan),
                ),
            ).ok,
        )
        val other = mem.captureUser("assistant", "other", "账本秘密", ClockDomain.WALL_CLOCK)
        assertTrue(
            mem.commit(
                MemoryBatch(
                    spaceId = "assistant",
                    ownerId = "other",
                    writerKind = MemoryWriterKind.EXTRACTOR,
                    writerId = "ex",
                    writerRunId = "run-o",
                    commands = listOf(
                        EpisodeCommand(
                            idempotencyKey = "secret",
                            occurredAt = ClockStamp(ClockDomain.WALL_CLOCK, 5),
                            rendered = RenderedText("账本秘密"),
                            sources = listOf(source(other)),
                        ),
                    ),
                    commitRawIds = listOf(other),
                ),
            ).ok,
        )

        val now = ClockStamp(ClockDomain.WALL_CLOCK, 20)
        val search = mem.recall(
            RecallRequest(
                spaceId = "assistant",
                ownerId = "user",
                query = "杭州",
                at = now,
                sessionId = "new",
            ),
        )
        assertTrue(search.hits.none { it.text.contains("杭州") })
        assertTrue(search.hits.none { it.text.contains("今晚打算") })
        assertTrue(search.hits.none { it.text.contains("账本秘密") })

        val sameSession = mem.recall(
            RecallRequest(
                spaceId = "assistant",
                ownerId = "user",
                query = "火锅",
                at = now,
                sessionId = "old",
            ),
        )
        assertTrue(sameSession.hits.any { it.text.contains("今晚打算火锅") })
    }

    @Test
    fun userLockBlocksExtractorOverlayOnAnyScope() = runTest {
        val mem = runtime().seedAssistant()
        val raw = mem.captureUser("assistant", "user", "花生过敏", ClockDomain.WALL_CLOCK)
        assertTrue(
            mem.commit(
                stateBatch(
                    "assistant",
                    "user",
                    MemoryWriterKind.USER_EDIT,
                    StateCommand(
                        fieldId = "allergies",
                        payload = payload("花生"),
                        rendered = RenderedText("花生"),
                        sources = listOf(SourceRef(SourceType.USER_EDIT, "ui")),
                        validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                    ),
                    rawIds = emptyList(),
                ),
            ).ok,
        )
        val overlay = mem.commit(
            stateBatch(
                "assistant",
                "user",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "allergies",
                    payload = payload("无"),
                    rendered = RenderedText("无过敏"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                    scope = MemoryScope.SESSION,
                    scopeId = "s1",
                ),
            ),
        )
        assertEquals(MemoryCodes.USER_LOCK, overlay.error?.code)

        val candidate = mem.commit(
            stateBatch(
                "assistant",
                "user",
                MemoryWriterKind.EXTRACTOR,
                StateCommand(
                    fieldId = "allergies",
                    payload = payload("无"),
                    rendered = RenderedText("无过敏"),
                    sources = listOf(source(raw)),
                    validFrom = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                    targetLifecycle = TargetLifecycle.CANDIDATE,
                    scope = MemoryScope.SESSION,
                    scopeId = "s1",
                ),
                runId = "run-c",
            ),
        )
        assertTrue(candidate.ok)
        val at = mem.getStates(
            StateReadRequest(
                spaceId = "assistant",
                ownerId = "user",
                at = ClockStamp(ClockDomain.WALL_CLOCK, 3),
                selectors = setOf(StateSelector("allergies")),
                sessionId = "s1",
            ),
        )
        assertEquals("花生", at.states["allergies"]?.text)
    }

    @Test
    fun embeddingIsAsyncAndOptional() = runTest {
        val mem = runtime().seedAssistant()
        val raw = mem.captureUser("assistant", "user", "面试", ClockDomain.WALL_CLOCK)
        val committed = mem.commit(
            MemoryBatch(
                spaceId = "assistant",
                ownerId = "user",
                writerKind = MemoryWriterKind.EXTRACTOR,
                writerId = "ex",
                writerRunId = "run-v",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "interview",
                        occurredAt = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                        rendered = RenderedText("那场对谈"),
                        sources = listOf(source(raw)),
                    ),
                    EpisodeCommand(
                        idempotencyKey = "dinner",
                        occurredAt = ClockStamp(ClockDomain.WALL_CLOCK, 1),
                        rendered = RenderedText("晚饭"),
                        sources = listOf(source(raw)),
                    ),
                ),
                commitRawIds = listOf(raw),
            ),
        )
        assertTrue(committed.ok)
        assertEquals(2, mem.indexHealth("assistant").embeddingPending)

        val interviewId = committed.itemIds[0]
        assertFalse(
            mem.putEmbedding(
                EmbeddingPut(interviewId, "default", floatArrayOf(), textHash = "nope"),
            ),
        )
        assertTrue(
            mem.recall(
                RecallRequest(
                    spaceId = "assistant",
                    ownerId = "user",
                    query = "那场对谈",
                    at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                ),
            ).hits.any { it.text.contains("那场对谈") },
        )

        assertTrue(
            mem.putEmbedding(EmbeddingPut(interviewId, "default", floatArrayOf(1f, 0f, 0f))),
        )
        val dinnerId = committed.itemIds[1]
        assertTrue(
            mem.putEmbedding(EmbeddingPut(dinnerId, "default", floatArrayOf(0f, 1f, 0f))),
        )
        assertEquals(0, mem.indexHealth("assistant").embeddingPending)

        val semantic = mem.recall(
            RecallRequest(
                spaceId = "assistant",
                ownerId = "user",
                query = "",
                queryVector = floatArrayOf(0.99f, 0.01f, 0f),
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
            ),
        )
        assertTrue(semantic.hits.any { it.channel == "VECTOR" && it.text.contains("那场对谈") })
        assertTrue(semantic.hits.none { it.channel == "VECTOR" && it.text.contains("晚饭") })

        (mem as SqliteLedgerRuntime).dropEmbeddingStorage()
        val afterDrop = mem.recall(
            RecallRequest(
                spaceId = "assistant",
                ownerId = "user",
                query = "那场对谈",
                queryVector = floatArrayOf(1f, 0f, 0f),
                at = ClockStamp(ClockDomain.WALL_CLOCK, 2),
                requiredFields = listOf(RequiredField("location", OnMissing.SKIP)),
                contextContractId = "a",
                contextContractVersion = "1",
            ),
        )
        assertTrue(afterDrop.hits.any { it.text.contains("那场对谈") })
        assertTrue(afterDrop.hits.none { it.channel == "VECTOR" })
    }

    @Test
    fun reflectionNeedsEvidenceAndHonorsAsOf() = runTest {
        val mem = runtime().seedNovel()
        val raw = mem.captureUser("novel:linwan", "林晚", "假腰牌", ClockDomain.STORY_TIME)
        val episode = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.HOST,
                writerId = "host",
                writerRunId = "ep",
                commands = listOf(
                    EpisodeCommand(
                        idempotencyKey = "ch3.fake.badge",
                        occurredAt = ClockStamp(ClockDomain.STORY_TIME, 3),
                        rendered = RenderedText("假腰牌"),
                        sources = listOf(source(raw)),
                    ),
                ),
                commitRawIds = listOf(raw),
            ),
        )
        assertTrue(episode.ok)

        val noEvidence = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.HOST,
                writerId = "host",
                writerRunId = "r0",
                commands = listOf(
                    ReflectionCommand(
                        memoryKey = "zhao-knew",
                        rendered = RenderedText("赵从第3回就知道假腰牌"),
                        sources = listOf(source(raw)),
                        validFrom = ClockStamp(ClockDomain.STORY_TIME, 80),
                    ),
                ),
            ),
        )
        assertEquals(MemoryCodes.MISSING_EVIDENCE, noEvidence.error?.code)

        val candidate = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.HOST,
                writerId = "host",
                writerRunId = "r1",
                commands = listOf(
                    ReflectionCommand(
                        memoryKey = "zhao-knew",
                        rendered = RenderedText("赵从第3回就知道假腰牌"),
                        sources = listOf(source(raw)),
                        validFrom = ClockStamp(ClockDomain.STORY_TIME, 80),
                        targetLifecycle = TargetLifecycle.CANDIDATE,
                    ),
                ),
            ),
        )
        assertTrue(candidate.ok)

        val active = mem.commit(
            MemoryBatch(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                writerKind = MemoryWriterKind.HOST,
                writerId = "host",
                writerRunId = "r2",
                commands = listOf(
                    ReflectionCommand(
                        memoryKey = "zhao-knew",
                        rendered = RenderedText("赵从第3回就知道假腰牌"),
                        sources = listOf(source(raw)),
                        validFrom = ClockStamp(ClockDomain.STORY_TIME, 80),
                        evidence = listOf(EvidenceRef(episode.itemIds.single())),
                    ),
                ),
            ),
        )
        assertTrue(active.ok)

        val at30 = mem.recall(
            RecallRequest(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                query = "假腰牌",
                at = ClockStamp(ClockDomain.STORY_TIME, 30),
            ),
        )
        assertTrue(at30.hits.none { it.kind == MemoryKind.REFLECTION })
        assertTrue(at30.hits.any { it.kind == MemoryKind.EPISODE && it.text.contains("假腰牌") })

        val at80 = mem.recall(
            RecallRequest(
                spaceId = "novel:linwan",
                ownerId = "林晚",
                query = "假腰牌",
                at = ClockStamp(ClockDomain.STORY_TIME, 80),
            ),
        )
        assertTrue(at80.hits.any { it.kind == MemoryKind.REFLECTION && it.text.contains("赵从第3回") })
    }
}
