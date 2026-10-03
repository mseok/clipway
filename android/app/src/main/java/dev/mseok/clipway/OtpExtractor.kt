package dev.mseok.clipway

/** Pulls a verification code out of an SMS body. Returns null for ordinary messages. */
object OtpExtractor {
    private val keyword = Regex(
        "인증\\s*번호|인증\\s*코드|보안\\s*코드|확인\\s*코드|verification code|security code|" +
            "passcode|one[- ]time|\\bOTP\\b|\\bcode\\b|\\bPIN\\b",
        RegexOption.IGNORE_CASE,
    )

    // 4-8 digits, or a 6-digit code written as "123 456" / "123-456". The lookarounds
    // reject pieces of phone numbers (1588-1234), dates (2026-10-03) and amounts (12000원).
    private val candidate = Regex("(?<!\\d)(?<!\\d-)(\\d{3}[- ]\\d{3}|\\d{4,8})(?!\\d)(?!-\\d)(?!\\s*[원년])")

    private const val WINDOW = 30

    fun extract(body: String): String? {
        val candidates = candidate.findAll(body).toList()
        if (candidates.isEmpty()) return null
        for (match in keyword.findAll(body)) {
            val after = candidates.firstOrNull {
                it.range.first >= match.range.last && it.range.first - match.range.last <= WINDOW
            }
            val before = candidates.lastOrNull {
                it.range.last <= match.range.first && match.range.first - it.range.last <= WINDOW
            }
            val hit = after ?: before ?: continue
            return hit.value.filter(Char::isDigit)
        }
        return null
    }
}
