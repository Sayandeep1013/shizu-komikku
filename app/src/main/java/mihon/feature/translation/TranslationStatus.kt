package mihon.feature.translation

/** What the live translator is doing with one page; shown on the reader's translate button. */
sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data object Queued : TranslationStatus
    data object Working : TranslationStatus
    data object Done : TranslationStatus
    data object ShowingOriginal : TranslationStatus
    data object NoText : TranslationStatus
    data class Failed(val reason: String) : TranslationStatus
}
