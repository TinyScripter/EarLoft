package de.kleinabersmart.earloft.core.network.podcast.ardsounds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Die Rubriken von ARD Sounds, aus denen Bücher kommen (`books_improvements.md` M9); [id] ist die der Schnittstelle. */
enum class ArdCategory(val id: String) {
    AUDIOBOOK("42914713"),
    RADIO_PLAY("42914712"),
}

/** Eine Sendung einer Rubrik: ein Buch in Teilen oder eine Reihe mit vielen Büchern. */
data class ArdShow(
    val id: String,
    val title: String,
    val publisher: String,
    val imageUrl: String?,
    val itemCount: Int,
)

/**
 * Ein Buch in einer Liste (Sendung, Suche). [key] ist die Kennung des Buchs ([ardBookKey]); [parts] die Zahl der Teile, soweit
 * bekannt, [durationMs] nur, wenn alle Teile in der Liste stehen.
 */
data class ArdBookSummary(
    val key: String,
    val title: String,
    val author: String,
    val narrator: String,
    val imageUrl: String?,
    val parts: Int,
    val durationMs: Long?,
    val publisher: String,
    val showTitle: String,
    val publishedAt: Long,
)

/** Ein Buch mit allen Teilen, wie es in die Bibliothek kommt. */
data class ArdBook(
    val key: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val imageUrl: String?,
    val publisher: String,
    val category: ArdCategory?,
    val webUrl: String?,
    val parts: List<ArdPart>,
) {
    val durationMs: Long get() = parts.sumOf { it.durationMs }

    /** Bis wann das Buch ganz abrufbar ist: der früheste Teil mit Ablauf; `null` = unbefristet bzw. unbekannt. */
    val availableUntil: Long? get() = parts.mapNotNull { it.availableUntil }.minOrNull()
}

/** Ein Teil eines Buchs: eine Audiodatei. [title] ist der Name des Kapitels. */
data class ArdPart(
    val id: String,
    val title: String,
    val audioUrl: String,
    val mimeType: String?,
    val durationMs: Long,
    val availableUntil: Long?,
)

/**
 * Hörbücher und Hörspiele aus ARD Sounds (`books_improvements.md` M9) über dieselbe GraphQL-Schnittstelle wie die Podcasts
 * ([ArdSoundsApi]). Was ein Buch ist, entscheidet [ardBookKey]; jeder Fehler kommt als `IOException`.
 */
@Singleton
class ArdSoundsBooks @Inject constructor(private val api: ArdSoundsApi) {

    /** Die Sendungen einer Rubrik in der Reihenfolge von ARD Sounds. */
    suspend fun shows(category: ArdCategory): List<ArdShow> {
        val data = api.query(CATEGORY_QUERY, buildJsonObject { put("id", category.id) }, CategoryData.serializer())
        return data.editorialCategory?.sections.orEmpty()
            .filter { it.type == "program_sets" }
            .flatMap { it.nodes.orEmpty() }
            .mapNotNull { show ->
                val id = show.coreId ?: return@mapNotNull null
                if ((show.numberOfElements ?: 1) <= 0) return@mapNotNull null
                ArdShow(
                    id = id,
                    title = show.title?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                    publisher = show.publicationService?.title?.trim().orEmpty(),
                    imageUrl = show.image?.sized(),
                    itemCount = show.numberOfElements ?: 0,
                )
            }
            .distinctBy { it.id }
    }

    /** Die Bücher der Sendung [showId], neueste zuerst. */
    suspend fun showBooks(showId: String): List<ArdBookSummary> {
        val variables = buildJsonObject {
            put("id", showId)
            put("first", SHOW_ITEM_LIMIT)
        }
        val items = api.query(SHOW_ITEMS_QUERY, variables, ShowItemsData.serializer()).programSetByCoreId?.items?.nodes.orEmpty()
        return summaries(items.filterNot(::isTrailer))
    }

    /** Bücher zu [term] aus den Rubriken Hörbuch und Hörspiel (die Suche von ARD Sounds findet einzelne Titel). */
    suspend fun search(term: String): List<ArdBookSummary> {
        val variables = buildJsonObject {
            put("query", term)
            put("limit", SEARCH_LIMIT)
        }
        val items = api.query(SEARCH_QUERY, variables, SearchItemsData.serializer()).search?.items?.nodes.orEmpty()
        return summaries(items.filter { it.category() != null })
    }

    /** Das Buch [key] mit allen veröffentlichten Teilen; `null`, wenn es keine (mehr) gibt. */
    suspend fun book(key: String): ArdBook? {
        val parsed = ArdBookKey.parse(key) ?: return null
        val items = when (parsed) {
            is ArdBookKey.Group -> fetchItems(buildJsonObject { put("groupId", buildJsonObject { put("equalTo", parsed.groupId) }) })
            is ArdBookKey.Item -> fetchItems(buildJsonObject { put("coreId", buildJsonObject { put("equalTo", parsed.coreId) }) })
            is ArdBookKey.Title -> fetchItems(
                buildJsonObject {
                    put("programSetId", buildJsonObject { put("equalTo", JsonPrimitive(parsed.programSetId)) })
                    put("title", buildJsonObject { put("startsWith", parsed.title) })
                },
            )
        }
        val parts = items.filter { ardBookKey(it.toKeyInput()) == key }
        return toBook(key, parts)
    }

    private suspend fun fetchItems(filter: JsonObject): List<ItemNode> {
        val variables = buildJsonObject {
            put("filter", filter)
            put("first", BOOK_PART_LIMIT)
        }
        return api.query(BOOK_QUERY, variables, ItemsData.serializer()).items?.nodes.orEmpty()
    }

    private fun summaries(items: List<ItemNode>): List<ArdBookSummary> =
        items.groupBy { ardBookKey(it.toKeyInput()) }.mapNotNull { (key, parts) ->
            val ordered = orderParts(parts)
            val first = ordered.firstOrNull() ?: return@mapNotNull null
            val meta = bookMeta(first)
            val total = first.maxGroupNumber ?: first.group?.count?.takeIf { first.group.type != SEASON } ?: ordered.size
            ArdBookSummary(
                key = key,
                title = meta.title,
                author = meta.author,
                narrator = meta.narrator,
                imageUrl = (first.image ?: first.programSet?.image)?.sized(),
                parts = maxOf(total, ordered.size),
                durationMs = ordered.sumOf { (it.duration ?: 0) * 1000L }.takeIf { ordered.size >= total },
                publisher = first.programSet?.publicationService?.title?.trim().orEmpty(),
                showTitle = first.programSet?.title?.trim().orEmpty(),
                publishedAt = parts.maxOf { it.publishedAt() },
            )
        }.sortedByDescending { it.publishedAt }

    private fun toBook(key: String, items: List<ItemNode>): ArdBook? {
        val ordered = orderParts(items)
        val first = ordered.firstOrNull() ?: return null
        val meta = bookMeta(first)
        val show = first.programSet
        // Ist das Buch die ganze Sendung, beschreibt die Sendung das Buch; sonst der erste Teil.
        val wholeShow = key == ArdBookKey.Group(show?.coreId.orEmpty()).value
        val description = (if (wholeShow) show?.description?.ifBlank { null } ?: show?.synopsis else null)
            ?: first.description?.ifBlank { null } ?: first.summary.orEmpty()
        val parts = ordered.mapIndexedNotNull { index, item ->
            val audio = item.audio() ?: return@mapIndexedNotNull null
            ArdPart(
                id = item.coreId ?: item.id ?: return@mapIndexedNotNull null,
                title = partTitle(item, index, ordered.size),
                audioUrl = audio.first,
                mimeType = audio.second,
                durationMs = (item.duration ?: 0) * 1000L,
                availableUntil = item.availableUntil(),
            )
        }
        if (parts.isEmpty()) return null
        return ArdBook(
            key = key,
            title = meta.title,
            author = meta.author,
            narrator = meta.narrator.ifEmpty { narratorIn(description).orEmpty() },
            description = description.trim(),
            imageUrl = (if (wholeShow) show?.image else first.image ?: show?.image)?.sized(),
            publisher = show?.publicationService?.title?.trim().orEmpty(),
            category = first.category(),
            webUrl = (if (wholeShow) show?.sharingUrl else first.sharingUrl)?.takeIf { it.startsWith("https://") },
            parts = parts,
        )
    }

    private fun bookMeta(first: ItemNode): ArdTitle {
        val grouped = first.groupId != null && first.group?.type != SEASON
        val raw = if (grouped) first.group?.title ?: first.programSet?.title else null
        val own = parseArdTitle(raw ?: numberedTitle(first.title.orEmpty())?.book ?: first.title.orEmpty())
        // Ohne Autor im Titel („Späte Rache“) nennt ihn oft die Sendung („Arthur Conan Doyle: Sherlock Holmes …“).
        val author = own.author.ifEmpty { parseArdTitle(first.programSet?.title.orEmpty()).author }
            .ifEmpty { first.programSet?.publicationService?.title?.trim().orEmpty() }
        val narrator = own.narrator.ifEmpty { parseArdTitle(first.title.orEmpty()).narrator }
        return ArdTitle(author, own.title.ifEmpty { first.title.orEmpty().trim() }, narrator)
    }

    private fun isTrailer(item: ItemNode): Boolean =
        (item.duration ?: 0) < TRAILER_MAX_S && item.title.orEmpty().contains("trailer", ignoreCase = true)

    private companion object {
        const val SEASON = "SEASON"
        const val SEARCH_LIMIT = 50
        const val SHOW_ITEM_LIMIT = 500
        const val BOOK_PART_LIMIT = 200
        const val TRAILER_MAX_S = 600
        const val IMAGE_WIDTH = 600

        const val ITEM_FIELDS = """
            id coreId title titleWithoutNumber groupId episodeNumber maxGroupNumber duration publishDate summary description sharingUrl
            group { type title count }
            image { url url1X1 }
            audios { url downloadUrl allowDownload mimeType }
            programSet {
              id coreId title description synopsis sharingUrl image { url url1X1 } publicationService { title }
              editorialCategoryId editorialCategoriesList { id }
            }
        """

        val CATEGORY_QUERY = """
            query(${'$'}id: ID!) {
              editorialCategory(id: ${'$'}id) {
                sections { type nodes { ... on ProgramSet { coreId title numberOfElements image { url url1X1 } publicationService { title } } } }
              }
            }
        """.trimIndent()

        val SHOW_ITEMS_QUERY = """
            query(${'$'}id: String!, ${'$'}first: Int) {
              programSetByCoreId(coreId: ${'$'}id) {
                items(first: ${'$'}first, orderBy: PUBLISH_DATE_DESC, condition: { isPublished: true }) { nodes { $ITEM_FIELDS } }
              }
            }
        """.trimIndent()

        val SEARCH_QUERY = """
            query(${'$'}query: String, ${'$'}limit: Int) {
              search(query: ${'$'}query, limit: ${'$'}limit, type: Items) { items { nodes { ... on Item { $ITEM_FIELDS } } } }
            }
        """.trimIndent()

        val BOOK_QUERY = """
            query(${'$'}filter: ItemFilter, ${'$'}first: Int) {
              items(first: ${'$'}first, filter: ${'$'}filter, condition: { isPublished: true }, orderBy: EPISODE_NUMBER_ASC) {
                nodes { $ITEM_FIELDS coreDocument }
              }
            }
        """.trimIndent()

        fun ImageNode.sized(): String? = (url1X1 ?: url)?.trim()?.ifEmpty { null }?.replace("{width}", IMAGE_WIDTH.toString())
    }
}

/** Kennung eines Buchs aus ARD Sounds in `books.remoteId`. */
internal sealed class ArdBookKey {
    abstract val value: String

    /** Die Teile einer Gruppe (`MULTIPART`, `SERIES`): ein Werk in Folgen. */
    data class Group(val groupId: String) : ArdBookKey() {
        override val value get() = "group:$groupId"
    }

    /** Nummerierte Titel einer Sendung ohne Gruppe („Tyll (3/8)“). [title] ohne Nummer. */
    data class Title(val programSetId: Long, val title: String) : ArdBookKey() {
        override val value get() = "title:$programSetId:$title"
    }

    /** Ein einzelner Titel. */
    data class Item(val coreId: String) : ArdBookKey() {
        override val value get() = "item:$coreId"
    }

    companion object {
        fun parse(key: String): ArdBookKey? = when {
            key.startsWith("group:") -> Group(key.removePrefix("group:")).takeIf { it.groupId.isNotEmpty() }
            key.startsWith("item:") -> Item(key.removePrefix("item:")).takeIf { it.coreId.isNotEmpty() }
            key.startsWith("title:") -> key.removePrefix("title:").split(':', limit = 2).takeIf { it.size == 2 }
                ?.let { (id, title) -> id.toLongOrNull()?.let { Title(it, title) } }
            else -> null
        }
    }
}

/** Was [ardBookKey] von einem Titel braucht. */
internal data class ArdKeyInput(
    val coreId: String?,
    val title: String,
    val groupId: String?,
    val groupType: String?,
    val programSetId: Long?,
)

/**
 * Zu welchem Buch ein Titel gehört (`books_improvements.md` M9): Gruppen außer Staffeln (`SEASON`, dort ist jeder Titel ein
 * Buch) fassen ihre Teile zusammen; ohne Gruppe nummerierte Titel einer Sendung („… (3/8)“, „…, Folge 3“) über den Titel ohne
 * Nummer; sonst ist der Titel selbst ein Buch.
 */
internal fun ardBookKey(input: ArdKeyInput): String {
    if (input.groupId != null && input.groupType != "SEASON") return ArdBookKey.Group(input.groupId).value
    val numbered = numberedTitle(input.title)
    if (numbered != null && input.programSetId != null) return ArdBookKey.Title(input.programSetId, numbered.book).value
    return ArdBookKey.Item(input.coreId.orEmpty()).value
}

/** Ein nummerierter Titel: das Buch, die Nummer des Teils und, was danach steht („Tyll (3/8): Minnesänger …“). */
internal data class NumberedTitle(val book: String, val number: Int, val label: String?, val rest: String)

/** „Buch (3/8)“, „Buch (3)“, „Buch, Folge 3“, „Buch – Teil 3“, jeweils mit optionalem Rest nach „:“ oder „–“. */
internal fun numberedTitle(title: String): NumberedTitle? {
    val text = title.trim()
    PARENTHESIS_NUMBER.matchEntire(text)?.let { m ->
        val book = m.groupValues[1].trim().trimEnd(':', ',', '-', '–').trim()
        if (book.isNotEmpty()) return NumberedTitle(book, m.groupValues[2].toInt(), null, m.groupValues[3].trim())
    }
    LABEL_NUMBER.matchEntire(text)?.let { m ->
        val book = m.groupValues[1].trim()
        if (book.isNotEmpty()) return NumberedTitle(book, m.groupValues[3].toInt(), m.groupValues[2], m.groupValues[4].trim())
    }
    return null
}

private val PARENTHESIS_NUMBER = Regex("""^(.*?)\s*\((\d{1,3})(?:\s*/\s*\d{1,3})?\)\s*(?:[:–-]\s*(.*))?$""")
private val LABEL_NUMBER = Regex("""^(.*?)\s*(?:[,:–-]\s*)?\b(Folge|Teil|Kapitel|Episode)\s+(\d{1,3})\b\s*(?:[:–-]\s*(.*))?$""", RegexOption.IGNORE_CASE)

/** Autor, Titel und Sprecher eines Titels aus ARD Sounds; fehlt eine Angabe, ist sie leer. */
internal data class ArdTitle(val author: String, val title: String, val narrator: String)

/**
 * Liest Autor, Titel und Sprecher aus den üblichen Schreibweisen: „Autor: Titel“, „Autor: "Titel" | Gelesen von Sprecher“,
 * „"Titel" von Autor“; Zusätze des Senders („ - WDR 5 Hörbuch“, „| WDR“) fallen weg.
 */
internal fun parseArdTitle(raw: String): ArdTitle {
    var text = raw.trim()
    var narrator = ""
    val pieces = text.split(" | ")
    if (pieces.size > 1) {
        text = pieces.first()
        narrator = pieces.drop(1).firstNotNullOfOrNull { narratorIn(it) }.orEmpty()
    }
    text = SENDER_SUFFIX.replace(text, "").trim()
    narratorIn(text)?.let { found ->
        narrator = narrator.ifEmpty { found }
        text = NARRATOR_PHRASE.replace(text, "").trim().trimEnd(',', '-', '–', '|').trim()
    }
    BY_AUTHOR.matchEntire(text)?.let { m -> return ArdTitle(m.groupValues[2].trim(), unquote(m.groupValues[1]), narrator) }
    val colon = text.indexOf(": ")
    if (colon > 0) {
        val author = text.substring(0, colon).trim()
        if (looksLikeName(author)) return ArdTitle(author, unquote(text.substring(colon + 2)), narrator)
    }
    return ArdTitle("", unquote(text), narrator)
}

/** Der Sprecher aus „Gelesen von …“, „Lesung mit …“, „Es liest …“; `null` ohne solche Angabe. */
internal fun narratorIn(text: String): String? =
    NARRATOR_PHRASE.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }

private val NARRATOR_PHRASE = Regex("""(?:gelesen von|lesung mit|es liest|liest:|sprecherin:|sprecher:)\s+([^\n|.;()]+)""", RegexOption.IGNORE_CASE)
private val SENDER_SUFFIX = Regex(
    """\s*[-–|]\s*(?:(?:WDR|NDR|SWR|BR|HR|MDR|RBB|SR|RB|DLF|Deutschlandfunk|Radio Bremen)(?:\s*\d+)?(?:\s+\S+)?\s*(?:Hörbuch|Hörspiel)?|Hörbuch|Hörspiel)\s*$""",
    RegexOption.IGNORE_CASE,
)
private val BY_AUTHOR = Regex("""^[„"“”]([^"“”„]+)[„"“”]\s+(?:von|nach)\s+(.+)$""")

private fun unquote(text: String): String = text.trim().trim('"', '„', '“', '”', '»', '«').trim()

/** Ein Name hat zwei bis fünf Wörter und keine Ziffern („H. G. Wells“, „Emily Brontë“), nicht „Tatort“ oder „1001 Nacht“. */
private fun looksLikeName(text: String): Boolean {
    val words = text.split(' ').filter { it.isNotBlank() }
    return words.size in 2..5 && text.none { it.isDigit() } && words.all { it.first().isUpperCase() || it in NAME_PARTICLES }
}

private val NAME_PARTICLES = setOf("von", "van", "de", "der", "den", "du", "di", "da", "le", "la", "zu", "y")

/** Teile in der Reihenfolge des Buchs: nach Nummer, ohne Nummer nach Veröffentlichung; unnummerierte fallen weg, wenn es nummerierte gibt. */
private fun orderParts(parts: List<ItemNode>): List<ItemNode> {
    val numbered = parts.filter { it.partNumber() != null }
    return if (numbered.isEmpty()) {
        parts.sortedBy { it.publishedAt() }
    } else {
        numbered.sortedWith(compareBy<ItemNode> { it.partNumber() }.thenBy { it.publishedAt() }).distinctBy { it.partNumber() }
    }
}

private fun ItemNode.partNumber(): Int? = episodeNumber?.takeIf { groupId != null && group?.type != "SEASON" } ?: numberedTitle(title.orEmpty())?.number

/** Name des Kapitels: was im Titel nach der Nummer steht, sonst „Teil n“ (bzw. die Bezeichnung des Titels, „Folge n“). */
private fun partTitle(item: ItemNode, index: Int, count: Int): String {
    val title = item.title.orEmpty().trim()
    if (count == 1) return title
    val numbered = numberedTitle(title)
    if (numbered != null) return numbered.rest.ifEmpty { "${numbered.label?.replaceFirstChar { it.uppercase() } ?: "Teil"} ${numbered.number}" }
    return title.ifEmpty { "Teil ${index + 1}" }
}

/** Die Fassung zum Herunterladen, sonst die zum Streamen (beides gewöhnliche Dateien), MP3 vor AAC. */
private fun ItemNode.audio(): Pair<String, String?>? {
    val all = audios.orEmpty()
    val chosen = all.firstOrNull { it.allowDownload == true && it.downloadUrl != null }?.let { it.downloadUrl!! to it.mimeType }
        ?: all.firstOrNull { it.url != null && it.mimeType.equals("audio/mp3", ignoreCase = true) }?.let { it.url!! to it.mimeType }
        ?: all.firstOrNull { it.url != null }?.let { it.url!! to it.mimeType }
        ?: return null
    return chosen.first to chosen.second?.let { if (it.equals("audio/mp3", ignoreCase = true)) "audio/mpeg" else it }
}

private fun ItemNode.publishedAt(): Long =
    publishDate?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0

private fun ItemNode.availableUntil(): Long? =
    (coreDocument?.get("availableTo") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Hörbuch vor Hörspiel; `null`, wenn die Sendung in keiner der beiden Rubriken steht. */
private fun ItemNode.category(): ArdCategory? {
    val ids = buildSet {
        programSet?.editorialCategoryId?.let(::add)
        programSet?.editorialCategoriesList.orEmpty().mapNotNullTo(this) { it.id }
    }
    return ArdCategory.entries.firstOrNull { it.id in ids }
}

private fun ItemNode.toKeyInput() = ArdKeyInput(coreId ?: id, title.orEmpty(), groupId, group?.type, programSet?.id?.toLongOrNull())

@Serializable
private data class CategoryData(val editorialCategory: CategoryNode? = null)

@Serializable
private data class CategoryNode(val sections: List<SectionNode>? = null)

@Serializable
private data class SectionNode(val type: String? = null, val nodes: List<ShowNode>? = null)

@Serializable
private data class ShowNode(
    val id: String? = null,
    val coreId: String? = null,
    val title: String? = null,
    val numberOfElements: Int? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val sharingUrl: String? = null,
    val image: ImageNode? = null,
    val publicationService: ServiceNode? = null,
    val editorialCategoryId: String? = null,
    val editorialCategoriesList: List<IdNode>? = null,
    val items: ItemConnection? = null,
)

@Serializable
private data class ShowItemsData(val programSetByCoreId: ShowNode? = null)

@Serializable
private data class SearchItemsData(val search: SearchItemsNode? = null)

@Serializable
private data class SearchItemsNode(val items: ItemConnection? = null)

@Serializable
private data class ItemsData(val items: ItemConnection? = null)

@Serializable
private data class ItemConnection(val nodes: List<ItemNode>? = null)

@Serializable
private data class ItemNode(
    val id: String? = null,
    val coreId: String? = null,
    val title: String? = null,
    val titleWithoutNumber: String? = null,
    val groupId: String? = null,
    val episodeNumber: Int? = null,
    val maxGroupNumber: Int? = null,
    val duration: Long? = null,
    val publishDate: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val sharingUrl: String? = null,
    val group: GroupNode? = null,
    val image: ImageNode? = null,
    val audios: List<AudioNode>? = null,
    val programSet: ShowNode? = null,
    val coreDocument: JsonObject? = null,
)

@Serializable
private data class GroupNode(val type: String? = null, val title: String? = null, val count: Int? = null)

@Serializable
private data class ImageNode(val url: String? = null, val url1X1: String? = null)

@Serializable
private data class ServiceNode(val title: String? = null)

@Serializable
private data class IdNode(val id: String? = null)

@Serializable
private data class AudioNode(
    val url: String? = null,
    val downloadUrl: String? = null,
    val allowDownload: Boolean? = null,
    val mimeType: String? = null,
)
