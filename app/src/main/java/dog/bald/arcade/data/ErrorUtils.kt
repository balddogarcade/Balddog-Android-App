package dog.bald.arcade.data

import io.github.jan.supabase.exceptions.RestException

private val URL_HEADERS_BLOCK_REGEX = Regex("""(?is)\r?\n\s*(?:URL|Headers)\s*:.*$""")
private val SENSITIVE_TOKEN_REGEX = Regex(
    """(?i)(Bearer\s+[A-Za-z0-9\-._~+/]+=*|apikey\s*[:=]\s*\[?[^\]\s,]+]?|Authorization\s*[:=]\s*\[?[^\]]+]?)"""
)

/**
 * Returns a user-facing error message with HTTP URLs, request headers, and auth tokens stripped.
 */
fun Throwable.toUserMessage(fallback: String = "Ocurrió un error inesperado"): String {
    val raw = if (this is RestException && error.isNotBlank()) {
        error
    } else {
        message
    } ?: return fallback

    val cleaned = raw
        .replace(URL_HEADERS_BLOCK_REGEX, "")
        .replace(SENSITIVE_TOKEN_REGEX, "[redacted]")
        .trim()

    return cleaned.ifEmpty { fallback }
}
