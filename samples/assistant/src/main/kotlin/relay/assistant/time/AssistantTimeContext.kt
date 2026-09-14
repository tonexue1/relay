package relay.assistant.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import relay.agent.ContextAugmentation
import relay.agent.ContextAugmenter
import relay.llm.model.Message

fun currentTimeAnchor(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val local = now.atZone(zoneId)
    return "当前时间：${local.toLocalDate().format(DATE_FORMAT)} ${local.dayOfWeek.chinese} " +
        "${local.toLocalTime().format(TIME_FORMAT)}（${zoneId.id}）"
}

fun currentTimeContextAugmenter(
    now: () -> Instant = { Instant.now() },
    zoneId: ZoneId = ZoneId.systemDefault(),
): ContextAugmenter = ContextAugmenter {
    ContextAugmentation(listOf(Message.user(currentTimeAnchor(now(), zoneId))))
}

fun temporalUserHistory(
    text: String,
    sentAt: Instant,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val sent = sentAt.atZone(zoneId)
    val dueDate = relativeDueDate(text, sent.toLocalDate())
    val prefix = "发送于 ${sent.toLocalDate().format(DATE_FORMAT)} ${sent.toLocalTime().format(TIME_FORMAT)}"
    return if (dueDate != null && dueDate.isBefore(now.atZone(zoneId).toLocalDate())) {
        "【历史临时安排已过期：$prefix。原文：$text。不得作为今天待办。】"
    } else {
        "【$prefix】\n$text"
    }
}

private fun relativeDueDate(text: String, baseDate: java.time.LocalDate) = when {
    "后天" in text -> baseDate.plusDays(2)
    "明天" in text -> baseDate.plusDays(1)
    "今天" in text || "今晚" in text || "今早" in text -> baseDate
    else -> null
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private val java.time.DayOfWeek.chinese: String
    get() = when (this) {
        java.time.DayOfWeek.MONDAY -> "星期一"
        java.time.DayOfWeek.TUESDAY -> "星期二"
        java.time.DayOfWeek.WEDNESDAY -> "星期三"
        java.time.DayOfWeek.THURSDAY -> "星期四"
        java.time.DayOfWeek.FRIDAY -> "星期五"
        java.time.DayOfWeek.SATURDAY -> "星期六"
        java.time.DayOfWeek.SUNDAY -> "星期日"
    }
