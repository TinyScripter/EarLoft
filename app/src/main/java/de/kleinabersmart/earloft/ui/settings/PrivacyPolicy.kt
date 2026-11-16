package de.kleinabersmart.earloft.ui.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** Ein Absatz der Datenschutzerklärung. Text-Inhalte enthalten noch die Inline-Auszeichnung (siehe [policyText]). */
internal sealed interface PolicyBlock {
    data class Heading(val level: Int, val text: String) : PolicyBlock
    data class Paragraph(val text: String) : PolicyBlock
    data class Bullet(val text: String) : PolicyBlock
    data object Divider : PolicyBlock
}

/**
 * Zerlegt das Markdown von `PRIVACY.md` in Absätze. Unterstützt nur, was die Datei nutzt: Überschriften (`#`),
 * Trennlinien (`---`), Aufzählungen (`* `, Folgezeilen eingerückt), umbrochene Absätze und harte Umbrüche (zwei Leerzeichen).
 */
internal fun parsePolicy(markdown: String): List<PolicyBlock> {
    val blocks = mutableListOf<PolicyBlock>()
    var current: StringBuilder? = null
    var isBullet = false
    var hardBreak = false
    fun flush() {
        current?.let { blocks += if (isBullet) PolicyBlock.Bullet(it.toString()) else PolicyBlock.Paragraph(it.toString()) }
        current = null
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line == "---" -> {
                flush()
                blocks += PolicyBlock.Divider
            }
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                blocks += PolicyBlock.Heading(level, line.drop(level).trim())
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                flush()
                current = StringBuilder(line.drop(2).trim())
                isBullet = true
            }
            else -> {
                val text = current
                if (text == null) {
                    current = StringBuilder(line)
                    isBullet = false
                } else {
                    text.append(if (hardBreak) '\n' else ' ').append(line)
                }
            }
        }
        hardBreak = raw.endsWith("  ")
    }
    flush()
    return blocks
}

private val urlPattern = Regex("""https?://[^\s)]+""")

/** Inline-Auszeichnung eines Absatzes: `**fett**`, `*kursiv*`, `` `Code` `` und nackte Web-Adressen als Links. */
internal fun policyText(text: String, linkStyles: TextLinkStyles? = null): AnnotatedString = buildAnnotatedString {
    var boldIndex: Int? = null
    var italicIndex: Int? = null
    var i = 0
    while (i < text.length) {
        val url = if (text.startsWith("http", i)) urlPattern.matchAt(text, i)?.value?.trimEnd('.', ',', ';', ':') else null
        when {
            url != null -> {
                withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                i += url.length
            }
            text.startsWith("**", i) -> {
                val open = boldIndex
                if (open == null) {
                    boldIndex = pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                } else {
                    pop(open)
                    boldIndex = null
                }
                i += 2
            }
            text[i] == '*' -> {
                val open = italicIndex
                if (open == null) {
                    italicIndex = pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                } else {
                    pop(open)
                    italicIndex = null
                }
                i++
            }
            text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text, i + 1, end) }
                i = end + 1
            }
            else -> append(text[i++])
        }
    }
}
