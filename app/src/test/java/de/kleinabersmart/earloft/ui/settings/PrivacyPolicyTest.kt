package de.kleinabersmart.earloft.ui.settings

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyPolicyTest {

    @Test
    fun `blocks with wrapped paragraphs, bullets and hard breaks`() {
        val blocks = parsePolicy(
            """
            # Title

            **Last updated:** today  
            **ID:** x

            ## 1. Section

            * **First:** wrapped
              continuation
            * Second
            ---
            Plain text
            over two lines
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                PolicyBlock.Heading(1, "Title"),
                PolicyBlock.Paragraph("**Last updated:** today\n**ID:** x"),
                PolicyBlock.Heading(2, "1. Section"),
                PolicyBlock.Bullet("**First:** wrapped continuation"),
                PolicyBlock.Bullet("Second"),
                PolicyBlock.Divider,
                PolicyBlock.Paragraph("Plain text over two lines"),
            ),
            blocks,
        )
    }

    @Test
    fun `inline markup becomes styles and links`() {
        val text = policyText("**Bold** and *italic* with `CODE`, see https://example.org/x.")
        assertEquals("Bold and italic with CODE, see https://example.org/x.", text.text)

        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("Bold", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("italic", text.text.substring(italic.start, italic.end))
        val code = text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("CODE", text.text.substring(code.start, code.end))

        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://example.org/x", (link.item as LinkAnnotation.Url).url)
        assertEquals("https://example.org/x", text.text.substring(link.start, link.end))
    }

    @Test
    fun `the real privacy policies leave no markup behind`() {
        val texts = listOf("PRIVACY.md", "PRIVACY.de.md").flatMap { parsePolicy(File("../$it").readText()) }.mapNotNull {
            when (it) {
                is PolicyBlock.Heading -> it.text
                is PolicyBlock.Paragraph -> it.text
                is PolicyBlock.Bullet -> it.text
                PolicyBlock.Divider -> null
            }
        }
        texts.map { policyText(it).text }.forEach { text ->
            assertTrue("Markup left in: $text", '*' !in text && '`' !in text && !text.startsWith("#"))
        }
    }
}
