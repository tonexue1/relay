package relay.llm

/**
 * Uniform failure model across providers.
 *
 * [retryable] marks failures worth retrying with backoff; `RetryInterceptor` relies on it
 * rather than on provider-specific status codes.
 */
sealed class RelayLlmException(
    message: String,
    val providerId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    open val retryable: Boolean get() = false

    /** Transport failure: connection refused, DNS, TLS, socket reset. */
    class Network(
        message: String,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause) {
        override val retryable: Boolean get() = true
    }

    class Timeout(
        message: String,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause) {
        override val retryable: Boolean get() = true
    }

    /** HTTP 429. [retryAfterMillis] is populated when the provider sends `Retry-After`. */
    class RateLimited(
        message: String,
        val retryAfterMillis: Long? = null,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause) {
        override val retryable: Boolean get() = true
    }

    /** HTTP 401/403. */
    class Auth(
        message: String,
        val statusCode: Int,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause)

    /** HTTP 5xx. */
    class Server(
        message: String,
        val statusCode: Int,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause) {
        override val retryable: Boolean get() = true
    }

    /** HTTP 4xx other than 401/403/429, or a malformed response body. */
    class InvalidRequest(
        message: String,
        val statusCode: Int? = null,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause)

    class Unknown(
        message: String,
        providerId: String? = null,
        cause: Throwable? = null,
    ) : RelayLlmException(message, providerId, cause)
}
