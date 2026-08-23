package relay.memory.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import relay.agent.FunTool
import relay.agent.Tool
import relay.memory.IngestError
import relay.memory.MemoryHit
import relay.memory.MemoryStore
import relay.memory.ReviewItem
import relay.memory.TripleDraft

/**
 * Model-facing projection of [MemoryStore]. [graphId] is bound here so a call cannot hop graphs.
 * Agents pick the subset they need; this module does not attach them.
 */
val MEMORY_DAY_TOOLS: Set<String> = setOf("memory_query", "memory_facts")

val MEMORY_NIGHT_TOOLS: Set<String> = setOf(
    "memory_recent",
    "memory_neighborhood",
    "memory_merge_nodes",
    "memory_facts",
    "memory_ingest",
)

fun MemoryStore.dayTools(graphId: String): List<Tool> =
    graphTools(graphId).filter { it.def.name in MEMORY_DAY_TOOLS }

fun MemoryStore.nightTools(graphId: String): List<Tool> =
    graphTools(graphId).filter { it.def.name in MEMORY_NIGHT_TOOLS }

fun MemoryStore.graphTools(graphId: String): List<Tool> = listOf(
    ingestTool(graphId),
    queryTool(graphId),
    factsTool(graphId),
    recentTool(graphId),
    neighborhoodTool(graphId),
    mergeNodesTool(graphId),
    forgetTool(graphId),
    pendingReviewTool(graphId),
    resolveReviewTool(graphId),
)

private fun MemoryStore.ingestTool(graphId: String): Tool = FunTool(
    name = "memory_ingest",
    description = "Write triples to the personal graph. p must be in the closed set. " +
        "Unknown p or empty fields come back in errors; valid triples in the same batch still write. " +
        "Set retract=true to withdraw a matching live edge.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("triples") {
                put("type", "array")
                put("description", "Each item is s, p, o; optional retract")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("s") { put("type", "string") }
                        putJsonObject("p") { put("type", "string") }
                        putJsonObject("o") { put("type", "string") }
                        putJsonObject("retract") { put("type", "boolean") }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("s"))
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive("o"))
                    }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("triples")) }
    },
) { args ->
    val drafts = args.obj().array("triples").map { item ->
        val row = item.jsonObject
        TripleDraft(
            graphId = graphId,
            s = row.str("s"),
            p = row.str("p"),
            o = row.str("o"),
            retract = row["retract"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
    ingest(drafts).errors.toIngestJson()
}

private fun MemoryStore.queryTool(graphId: String): Tool = FunTool(
    name = "memory_query",
    description = "Literal FTS plus one hop. Query words that exist in the graph (花生), not hints (火锅).",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
            putJsonObject("budget_chars") { put("type", "integer") }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    },
) { args ->
    val obj = args.obj()
    query(
        graphId = graphId,
        text = obj.str("text"),
        budgetChars = obj["budget_chars"]?.jsonPrimitive?.longOrNull?.toInt() ?: 2000,
    ).toToolJson()
}

private fun MemoryStore.factsTool(graphId: String): Tool = FunTool(
    name = "memory_facts",
    description = "Live edges at optional at (epoch ms). Filter by p and/or node name.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("p") { put("type", "string") }
            putJsonObject("node") { put("type", "string") }
            putJsonObject("at") { put("type", "integer") }
        }
    },
) { args ->
    val obj = args.obj()
    facts(
        graphId = graphId,
        at = obj["at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        p = obj["p"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        node = obj["node"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    ).toToolJson()
}

private fun MemoryStore.recentTool(graphId: String): Tool = FunTool(
    name = "memory_recent",
    description = "Edges whose system clock created/updated/expired is at or after since (epoch ms). Includes just-expired.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("since") { put("type", "integer") }
        }
        putJsonArray("required") { add(JsonPrimitive("since")) }
    },
) { args ->
    recent(graphId, since = args.obj().long("since")).toToolJson()
}

private fun MemoryStore.neighborhoodTool(graphId: String): Tool = FunTool(
    name = "memory_neighborhood",
    description = "Live edges touching any of the named nodes.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("node_names") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("node_names")) }
    },
) { args ->
    val names = args.obj().array("node_names").map { it.jsonPrimitive.content }
    neighborhood(graphId, names).toToolJson()
}

private fun MemoryStore.mergeNodesTool(graphId: String): Tool = FunTool(
    name = "memory_merge_nodes",
    description = "Merge a synonym node into the canonical name. keep=canonical, drop=alias. " +
        "Must merge: 美式咖啡→美式, 坐地铁→地铁, 离职/换工作→跳槽, 我妈→妈妈, 花生酱→花生, " +
        "功课→作业, 吃素→素食, 英文→英语, 杭州市→杭州. " +
        "Do not merge different cities, people, cat/dog, or distinct allergens. Does not delete rows.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("keep") { put("type", "string") }
            putJsonObject("drop") { put("type", "string") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("keep"))
            add(JsonPrimitive("drop"))
        }
    },
) { args ->
    val obj = args.obj()
    mergeNodes(graphId, keep = obj.str("keep"), drop = obj.str("drop"))
    """{"ok":true}"""
}

private fun MemoryStore.forgetTool(graphId: String): Tool = FunTool(
    name = "memory_forget",
    description = "Expire stale low-confidence live edges. Only sets expired_at.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("now") { put("type", "integer") }
        }
    },
) { args ->
    val now = args.obj()["now"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
    forget(graphId, now = now)
    """{"ok":true}"""
}

private fun MemoryStore.pendingReviewTool(graphId: String): Tool = FunTool(
    name = "memory_pending_review",
    description = "List functional-supersede reviews for this graph.",
) {
    pendingReview(graphId).toReviewJson()
}

private fun MemoryStore.resolveReviewTool(graphId: String): Tool = FunTool(
    name = "memory_resolve_review",
    description = "accept=true keeps the new edge; accept=false expires it.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("edge_id") { put("type", "string") }
            putJsonObject("accept") { put("type", "boolean") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("edge_id"))
            add(JsonPrimitive("accept"))
        }
    },
) { args ->
    val obj = args.obj()
    resolveReview(graphId, edgeId = obj.str("edge_id"), accept = obj.bool("accept"))
    """{"ok":true}"""
}

private fun MemoryHit.toToolJson(): String = buildJsonObject {
    putJsonArray("facts") {
        for (fact in facts) {
            add(
                buildJsonObject {
                    put("s", fact.s)
                    put("p", fact.p)
                    put("o", fact.o)
                },
            )
        }
    }
}.toString()

private fun List<IngestError>.toIngestJson(): String = buildJsonObject {
    putJsonArray("errors") {
        for (err in this@toIngestJson) {
            add(
                buildJsonObject {
                    put("s", err.s)
                    put("p", err.p)
                    put("o", err.o)
                    put("reason", err.reason)
                },
            )
        }
    }
}.toString()

private fun List<ReviewItem>.toReviewJson(): String = buildJsonObject {
    putJsonArray("reviews") {
        for (item in this@toReviewJson) {
            add(
                buildJsonObject {
                    put("edge_id", item.edgeId)
                    put("reason", item.reason)
                    put("s", item.s)
                    put("p", item.p)
                    put("o", item.o)
                },
            )
        }
    }
}.toString()

private fun String.obj(): JsonObject = Json.parseToJsonElement(ifBlank { "{}" }).jsonObject

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("missing string '$key'")

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: error("missing integer '$key'")

private fun JsonObject.bool(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: error("missing boolean '$key'")

private fun JsonObject.array(key: String): JsonArray =
    this[key]?.jsonArray ?: error("missing array '$key'")
