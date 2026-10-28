package mihon.feature.translation

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader pill showing what live translation is doing with the current page.
 * Tap: translate (or retry) this page now. Long-press: switch between original and translation.
 */
@Composable
fun TranslateButton(
    translator: LiveTranslator,
    modifier: Modifier = Modifier,
) {
    val page by translator.currentPage.collectAsState()
    val current = page ?: return
    if (!translator.isConfigured()) return
    val status by current.translationStatus.collectAsState()

    val failed = status is TranslationStatus.Failed
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        status == TranslationStatus.Done -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }.copy(alpha = 0.92f)
    val content = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        status == TranslationStatus.Done -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(50)

    Surface(
        shape = shape,
        color = container,
        contentColor = content,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = { translator.translateNow(current) },
                onLongClick = { translator.toggleOriginal(current) },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            when (status) {
                TranslationStatus.Queued, TranslationStatus.Working -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )
                else -> Icon(
                    imageVector = when (status) {
                        TranslationStatus.Done -> Icons.Outlined.CheckCircle
                        TranslationStatus.ShowingOriginal -> Icons.Outlined.Visibility
                        is TranslationStatus.Failed -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.Translate
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = label(status),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
        }
    }
}

@Composable
private fun label(status: TranslationStatus): String = when (status) {
    TranslationStatus.Idle -> stringResource(KMR.strings.translate_button_idle)
    TranslationStatus.Queued -> stringResource(KMR.strings.translate_button_queued)
    TranslationStatus.Working -> stringResource(KMR.strings.translate_button_working)
    TranslationStatus.Done -> stringResource(KMR.strings.translate_button_done)
    TranslationStatus.ShowingOriginal -> stringResource(KMR.strings.translate_button_original)
    TranslationStatus.NoText -> stringResource(KMR.strings.translate_button_no_text)
    is TranslationStatus.Failed -> stringResource(KMR.strings.translate_button_failed, status.reason)
}
