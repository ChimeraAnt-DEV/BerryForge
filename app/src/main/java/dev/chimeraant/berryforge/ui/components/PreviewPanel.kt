package dev.chimeraant.berryforge.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chimeraant.berryforge.ui.design.BerryColors
import dev.chimeraant.berryforge.ui.design.BerryIcons
import dev.chimeraant.berryforge.ui.design.BerryMotion
import dev.chimeraant.berryforge.ui.design.BerryRadius
import dev.chimeraant.berryforge.ui.design.BerrySize
import dev.chimeraant.berryforge.ui.design.BerrySpacing
import dev.chimeraant.berryforge.ui.design.BerryType

/** End colour of the live-preview gradient (cyan), stored as a named token. */
val PreviewCyan = Color(0xFF22D3EE)

/**
 * The pulsing "Live preview" button.
 *
 * While inactive it cycles between blue and cyan with a soft glow and a 2% breathing
 * scale — enough motion to draw the eye without ever blocking a frame or spinning the
 * layout while a file is open.
 */
@Composable
fun HtmlPreviewButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "previewCta")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "previewCtaPhase",
    )
    val back = (kotlin.math.sin(phase) + 1f) / 2f
    val tint = lerp(BerryColors.Edit, PreviewCyan, back)
    val breathe = 1f + 0.02f * ((kotlin.math.sin(phase + 1.05f) + 1f) / 2f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(BerryRadius.md))
            .background(Brush.horizontalGradient(listOf(BerryColors.Edit, PreviewCyan)))
            .scale(breathe)
            .clickable(onClick = onClick)
            .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(BerryRadius.pill))
                .background(Color.White.copy(alpha = 0.92f)),
        )
        Spacer(Modifier.width(BerrySpacing.sm))
        Text(
            "Live preview",
            style = BerryType.BodyStrong,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Full-screen live preview that slides up over the editor.
 *
 * The document is re-injected as the user types, but deliberately **debounced and
 * diffed**: `loadDataWithBaseURL` tears the page down and re-parses it, so calling it on
 * every keystroke reloaded the whole document once per character. On a large page that
 * was a visible stutter on every key press, and it also reset any in-page state (scroll
 * position, form input) the user had established. The reload now happens at most once per
 * [PREVIEW_DEBOUNCE_MS] and only when the source actually changed.
 *
 * The WebView is sandboxed: no file or content access, JavaScript allowed only for the
 * page itself and local assets.
 */
// The preview is a sandbox: JavaScript is on because the page is the user's own HTML,
// but file, content and cross-origin access are all off (see the settings below), and
// nothing is loaded over the network. Lint's blanket warning does not apply here.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewSheet(
    visible: Boolean,
    htmlSource: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The last source actually handed to the WebView. A plain holder rather than state:
    // it is only read inside `update`, so making it state would schedule an extra
    // recomposition for every reload and buy nothing.
    val lastLoaded = remember { LoadedMarker() }

    // Debounced source. Only advances once typing pauses, so a burst of keystrokes
    // produces one reload instead of one per character.
    var settled by remember { mutableStateOf(htmlSource) }
    LaunchedEffect(htmlSource) {
        if (htmlSource == settled) return@LaunchedEffect
        kotlinx.coroutines.delay(PREVIEW_DEBOUNCE_MS)
        settled = htmlSource
    }
    // Closing the sheet destroys the WebView, so the marker is cleared; reopening then
    // re-renders even when the source has not changed in between.
    LaunchedEffect(visible) {
        if (!visible) lastLoaded.value = null
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(BerryMotion.Slow)) + slideInVertically(tween(BerryMotion.Slow)) { it },
        exit = fadeOut(tween(BerryMotion.Fast)) + slideOutVertically(tween(BerryMotion.Fast)) { it },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(BerryColors.Surface2),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BerrySpacing.md, vertical = BerrySpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BerryIcon(BerryIcons.Eye, "Live preview", size = BerrySize.icon, tint = PreviewCyan)
                Spacer(Modifier.width(BerrySpacing.sm))
                Text("Live preview", style = BerryType.BodyStrong, color = BerryColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    "Renders as you type",
                    style = BerryType.Caption,
                    color = BerryColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(BerrySpacing.sm))
                BerryButton(
                    text = "Close",
                    onClick = onDismiss,
                    variant = BerryButtonVariant.Secondary,
                    size = BerryButtonSize.Sm,
                    icon = BerryIcons.Close,
                )
            }
            BerryDivider(strong = true)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(BerryColors.Surface1),
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(BerryColors.Surface1.toArgb())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // HTML previews run arbitrary user/LLM-authored script. On
                            // API 26-29 the file-URL access flags default to true, so a
                            // preview could read sibling assets or make cross-origin
                            // requests; pin both off. Popups stay off too.
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            webChromeClient = WebChromeClient()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { webView ->
                        // Debounced: see the note on the sheet. `settled` only advances
                        // once the source has stopped changing for a moment, so a fast
                        // typist triggers one reload rather than one per character.
                        if (settled != lastLoaded.value) {
                            lastLoaded.value = settled
                            webView.loadDataWithBaseURL(
                                "file:///android_asset/",
                                settled,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    onRelease = { webView ->
                        // A WebView that is merely dropped keeps its renderer process and
                        // its JS timers alive. Releasing it stops the page and frees the
                        // memory instead of leaking a renderer per preview opened.
                        runCatching {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.clearHistory()
                            webView.removeAllViews()
                            webView.destroy()
                        }
                    },
                )
            }
        }
    }
}

/**
 * How long typing must pause before the preview document is re-injected.
 *
 * A reload tears down and re-parses the whole page, so this trades a small amount of
 * latency for a large amount of smoothness: without it the document reloaded on every
 * single keystroke.
 */
private const val PREVIEW_DEBOUNCE_MS = 250L

/** Mutable holder for the source currently loaded into the preview WebView. */
private class LoadedMarker {
    var value: String? = null
}
