package mihon.feature.translation

import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Runs a request against the user's providers in priority order.
 *
 * Transient problems (network drops when the app is backgrounded, timeouts, 429/5xx) are retried
 * on the same provider with a short backoff; anything else (bad key, no credit, model refused)
 * moves straight on to the next provider that has a key. Only when every provider has failed does
 * the caller see an error, and it names what went wrong with each.
 */
class LlmRouter(
    baseClient: OkHttpClient,
    endpoints: List<Endpoint>,
) {
    val clients: List<LlmClient> = endpoints.map { LlmClient(baseClient, it) }

    /** Primary provider, used for vision decisions and cache keys. */
    val primary: LlmClient? get() = clients.firstOrNull()

    suspend fun <T> run(block: suspend (LlmClient) -> T): T {
        val errors = mutableListOf<String>()
        for (client in clients) {
            for (attempt in 0 until ATTEMPTS) {
                try {
                    return block(client)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val transient = isTransient(e)
                    val reason = shortReason(e)
                    xLogW("${client.label} attempt ${attempt + 1} failed (transient=$transient): $reason")
                    if (!transient || attempt == ATTEMPTS - 1) {
                        errors += "${client.label}: $reason"
                        break
                    }
                    delay(BACKOFF_MS[attempt])
                }
            }
        }
        throw TranslationException(errors.joinToString("; ").ifEmpty { "no provider configured" })
    }

    companion object {
        private const val ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(1_500, 5_000)

        fun isTransient(e: Throwable): Boolean = when (e) {
            is IOException -> true
            is TranslationException -> e.code == null || e.code == 408 || e.code == 409 || e.code == 425 ||
                e.code == 429 || e.code >= 500
            else -> false
        }

        fun shortReason(e: Throwable): String = when (e) {
            is java.net.SocketTimeoutException -> "timed out"
            is IOException -> "network error"
            else -> (e.message ?: e::class.java.simpleName).take(120)
        }
    }
}
