package mihon.feature.translation

import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a request against the user's providers in priority order.
 *
 * Transient problems (network drops when the app is backgrounded, timeouts, overload) are retried
 * on the same provider with a short backoff; anything else (bad key, quota used up, model refused)
 * moves straight on to the next provider that has a key. A provider that keeps failing is put on
 * a cooldown so later pages skip it instead of paying for the retries again. Only when every
 * provider has failed does the caller see an error, and it names what went wrong with each.
 */
class LlmRouter(
    baseClient: OkHttpClient,
    endpoints: List<Endpoint>,
) {
    val clients: List<LlmClient> = endpoints.map { LlmClient(baseClient, it) }

    private val cooldownUntil = ConcurrentHashMap<Provider, Long>()

    suspend fun <T> run(block: suspend (LlmClient) -> T): T {
        val now = System.currentTimeMillis()
        // Skip providers on cooldown, unless that would leave nothing to try.
        val ready = clients.filter { (cooldownUntil[it.endpoint.provider] ?: 0L) <= now }.ifEmpty { clients }
        val errors = mutableListOf<String>()
        for (client in ready) {
            for (attempt in 0 until ATTEMPTS) {
                try {
                    val result = block(client)
                    cooldownUntil.remove(client.endpoint.provider)
                    return result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val transient = isTransient(e)
                    val reason = shortReason(e)
                    xLogW("${client.label} attempt ${attempt + 1} failed (transient=$transient): $reason")
                    if (!transient || attempt == ATTEMPTS - 1) {
                        errors += "${client.label}: $reason"
                        cooldownUntil[client.endpoint.provider] = System.currentTimeMillis() + cooldownMs(e)
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
            // No code means the model answered but not usefully (refusal, empty, not JSON): another
            // provider is a better bet than asking the same one again. A used-up quota won't come
            // back within seconds either.
            is TranslationException ->
                e.code != null &&
                    !isQuotaExhausted(e) &&
                    (e.code == 408 || e.code == 409 || e.code == 425 || e.code == 429 || e.code >= 500)
            else -> false
        }

        /** 429 can mean "slow down" or "out of quota/credit"; only the first is worth retrying. */
        private fun isQuotaExhausted(e: TranslationException): Boolean {
            val message = e.message.orEmpty().lowercase()
            return (e.code == 429 || e.code == 402) &&
                ("quota" in message || "billing" in message || "credit" in message || "exhausted" in message)
        }

        private fun cooldownMs(e: Throwable): Long = when {
            e is TranslationException && isQuotaExhausted(e) -> 5 * 60_000L
            e is TranslationException && (e.code == 401 || e.code == 402 || e.code == 403) -> 10 * 60_000L
            else -> 60_000L
        }

        fun shortReason(e: Throwable): String = when (e) {
            is java.net.SocketTimeoutException -> "timed out"
            is IOException -> "network error"
            else -> (e.message ?: e::class.java.simpleName).take(120)
        }
    }
}
