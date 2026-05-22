package com.averycorp.prismtask.domain.automation

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Gson round-trip for the three automation sealed hierarchies. Each
 * polymorphic type is discriminated by a string field — `type` for
 * triggers/actions, `op` for conditions — so the JSON shape stays
 * human-readable + greppable in DAO blobs.
 *
 * `parse*` methods return null on malformed JSON rather than throwing.
 * Engine treats null trigger/action as an unparseable rule and logs an
 * error row — failure isolation per § A6.
 */
object AutomationJsonAdapter {

    val gson: Gson = GsonBuilder()
        // Preserve null members so structured tokens like the `@now` map
        // (`{"@now": null}` — see AutomationStarterLibrary) survive
        // round-trip; default Gson would drop them and corrupt template
        // semantics.
        .serializeNulls()
        .registerTypeAdapter(AutomationTrigger::class.java, TriggerAdapter)
        .registerTypeAdapter(AutomationCondition::class.java, ConditionAdapter)
        .registerTypeAdapter(AutomationAction::class.java, ActionAdapter)
        .create()

    fun encodeTrigger(t: AutomationTrigger): String = gson.toJson(t, AutomationTrigger::class.java)
    fun decodeTrigger(json: String): AutomationTrigger? = runCatching {
        gson.fromJson(json, AutomationTrigger::class.java)
    }.getOrNull()

    fun encodeCondition(c: AutomationCondition?): String? =
        c?.let { gson.toJson(it, AutomationCondition::class.java) }

    fun decodeCondition(json: String?): AutomationCondition? = json?.let {
        runCatching { gson.fromJson(it, AutomationCondition::class.java) }.getOrNull()
    }

    fun encodeActions(actions: List<AutomationAction>): String = JsonArray().also { arr ->
        actions.forEach { arr.add(ActionAdapter.serialize(it, AutomationAction::class.java, null)) }
    }.toString()

    fun decodeActions(json: String): List<AutomationAction> = runCatching {
        val arr = gson.fromJson(json, JsonArray::class.java) ?: return emptyList()
        arr.mapNotNull { el ->
            runCatching { ActionAdapter.deserialize(el, AutomationAction::class.java, null) }
                .getOrNull()
        }
    }.getOrDefault(emptyList())

    private object TriggerAdapter : JsonSerializer<AutomationTrigger>, JsonDeserializer<AutomationTrigger> {
        override fun serialize(
            src: AutomationTrigger,
            typeOfSrc: Type,
            context: JsonSerializationContext?
        ): JsonElement = JsonObject().apply {
            addProperty("type", src.type)
            when (src) {
                is AutomationTrigger.EntityEvent -> addProperty("eventKind", src.eventKind)
                is AutomationTrigger.TimeOfDay -> {
                    addProperty("hour", src.hour)
                    addProperty("minute", src.minute)
                }
                is AutomationTrigger.DayOfWeekTime -> {
                    addProperty("hour", src.hour)
                    addProperty("minute", src.minute)
                    add(
                        "daysOfWeek",
                        JsonArray().also { arr ->
                            src.daysOfWeek.forEach { arr.add(JsonPrimitive(it)) }
                        }
                    )
                }
                is AutomationTrigger.Composed -> addProperty("parentRuleId", src.parentRuleId)
                AutomationTrigger.Manual -> {}
            }
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext?
        ): AutomationTrigger {
            val obj = json.asJsonObject
            return when (val t = obj.get("type").asString) {
                AutomationTrigger.EntityEvent.TYPE ->
                    AutomationTrigger.EntityEvent(obj.get("eventKind").asString)
                AutomationTrigger.TimeOfDay.TYPE ->
                    AutomationTrigger.TimeOfDay(obj.get("hour").asInt, obj.get("minute").asInt)
                AutomationTrigger.DayOfWeekTime.TYPE ->
                    AutomationTrigger.DayOfWeekTime(
                        daysOfWeek = obj.getAsJsonArray("daysOfWeek")
                            .map { it.asString }
                            .toSet(),
                        hour = obj.get("hour").asInt,
                        minute = obj.get("minute").asInt
                    )
                AutomationTrigger.Manual.TYPE ->
                    AutomationTrigger.Manual
                AutomationTrigger.Composed.TYPE ->
                    AutomationTrigger.Composed(obj.get("parentRuleId").asLong)
                else -> throw IllegalArgumentException("unknown trigger type: $t")
            }
        }
    }

    private object ConditionAdapter : JsonSerializer<AutomationCondition>, JsonDeserializer<AutomationCondition> {
        override fun serialize(
            src: AutomationCondition,
            typeOfSrc: Type,
            context: JsonSerializationContext?
        ): JsonElement = JsonObject().apply {
            addProperty("op", src.op)
            when (src) {
                is AutomationCondition.Compare -> {
                    addProperty("field", src.field)
                    add("value", encodeValue(src.value))
                }
                is AutomationCondition.And -> add(
                    "children",
                    JsonArray().also { arr ->
                        src.children.forEach { arr.add(serialize(it, AutomationCondition::class.java, context)) }
                    }
                )
                is AutomationCondition.Or -> add(
                    "children",
                    JsonArray().also { arr ->
                        src.children.forEach { arr.add(serialize(it, AutomationCondition::class.java, context)) }
                    }
                )
                is AutomationCondition.Not -> add("child", serialize(src.child, AutomationCondition::class.java, context))
            }
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext?
        ): AutomationCondition {
            val obj = json.asJsonObject
            return when (val op = obj.get("op").asString) {
                "AND" -> AutomationCondition.And(
                    obj.getAsJsonArray("children").map { deserialize(it, typeOfT, context) }
                )
                "OR" -> AutomationCondition.Or(
                    obj.getAsJsonArray("children").map { deserialize(it, typeOfT, context) }
                )
                "NOT" -> AutomationCondition.Not(deserialize(obj.get("child"), typeOfT, context))
                else -> AutomationCondition.Compare(
                    opType = AutomationCondition.Op.valueOf(op),
                    field = obj.get("field").asString,
                    value = decodeValue(obj.get("value"))
                )
            }
        }
    }

    private object ActionAdapter : JsonSerializer<AutomationAction>, JsonDeserializer<AutomationAction> {
        override fun serialize(
            src: AutomationAction,
            typeOfSrc: Type,
            context: JsonSerializationContext?
        ): JsonElement = JsonObject().apply {
            addProperty("type", src.type)
            when (src) {
                is AutomationAction.Notify -> {
                    addProperty("title", src.title)
                    addProperty("body", src.body)
                    addProperty("priority", src.priority)
                }
                is AutomationAction.MutateTask -> add("updates", encodeMap(src.updates))
                is AutomationAction.MutateHabit -> add("updates", encodeMap(src.updates))
                is AutomationAction.MutateMedication -> add("updates", encodeMap(src.updates))
                is AutomationAction.ScheduleTimer -> {
                    addProperty("durationMinutes", src.durationMinutes)
                    addProperty("mode", src.mode)
                }
                is AutomationAction.ApplyBatch -> add(
                    "mutations",
                    JsonArray().also { arr ->
                        src.mutations.forEach { arr.add(encodeMap(it)) }
                    }
                )
                is AutomationAction.AiComplete -> {
                    addProperty("prompt", src.prompt)
                    src.targetField?.let { addProperty("targetField", it) }
                }
                is AutomationAction.AiSummarize -> {
                    addProperty("scope", src.scope)
                    addProperty("maxItems", src.maxItems)
                }
                is AutomationAction.LogMessage -> addProperty("message", src.message)
            }
        }

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext?
        ): AutomationAction {
            val obj = json.asJsonObject
            return when (val t = obj.get("type").asString) {
                "notify" -> AutomationAction.Notify(
                    title = obj.get("title").asString,
                    body = obj.get("body").asString,
                    priority = obj.get("priority")?.asInt ?: 0
                )
                "mutate.task" -> AutomationAction.MutateTask(decodeMap(obj.get("updates")))
                "mutate.habit" -> AutomationAction.MutateHabit(decodeMap(obj.get("updates")))
                "mutate.medication" -> AutomationAction.MutateMedication(decodeMap(obj.get("updates")))
                "schedule.timer" -> AutomationAction.ScheduleTimer(
                    durationMinutes = obj.get("durationMinutes").asInt,
                    mode = obj.get("mode")?.asString ?: "FOCUS"
                )
                "apply.batch" -> AutomationAction.ApplyBatch(
                    obj.getAsJsonArray("mutations").map { decodeMap(it) }
                )
                "ai.complete" -> AutomationAction.AiComplete(
                    prompt = obj.get("prompt").asString,
                    targetField = obj.get("targetField")?.asString
                )
                "ai.summarize" -> AutomationAction.AiSummarize(
                    scope = obj.get("scope").asString,
                    maxItems = obj.get("maxItems")?.asInt ?: 50
                )
                "log" -> AutomationAction.LogMessage(obj.get("message").asString)
                else -> throw IllegalArgumentException("unknown action type: $t")
            }
        }
    }

    private fun encodeValue(v: Any?): JsonElement = when (v) {
        null -> com.google.gson.JsonNull.INSTANCE
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray().also { arr -> v.forEach { arr.add(encodeValue(it)) } }
        is Map<*, *> -> encodeMap(v.mapKeys { it.key.toString() })
        else -> JsonPrimitive(v.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeValue(el: JsonElement?): Any? = when {
        el == null || el.isJsonNull -> null
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> {
                    if (p.asString.contains('.')) {
                        p.asDouble
                    } else {
                        // Narrow to Int when it fits — production callers
                        // provide Int literals (e.g. priority thresholds,
                        // weekday ints), and structural round-trip
                        // equality requires we preserve that.
                        // ConditionEvaluator coerces both sides to Double
                        // for numeric ops, so widening at runtime is fine.
                        val l = p.asNumber.toLong()
                        if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
                    }
                }
                else -> p.asString
            }
        }
        el.isJsonArray -> el.asJsonArray.map { decodeValue(it) }
        el.isJsonObject -> decodeMap(el)
        else -> null
    }

    private fun encodeMap(m: Map<String, Any?>): JsonObject = JsonObject().apply {
        m.forEach { (k, v) -> add(k, encodeValue(v)) }
    }

    private fun decodeMap(el: JsonElement): Map<String, Any?> {
        if (!el.isJsonObject) return emptyMap()
        val obj = el.asJsonObject
        val out = mutableMapOf<String, Any?>()
        for ((k, v) in obj.entrySet()) out[k] = decodeValue(v)
        return out
    }
}
