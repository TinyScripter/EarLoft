package de.kleinabersmart.earloft.core.network.podcast.ardsounds

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** The ARD Sounds categories books come from (`books_improvements.md` M9); [id] is the interface's one. */
enum class ArdCategory(val id: String) {
    AUDIOBOOK("42914713"),
    RADIO_PLAY("42914712"),
}

/** A show of a category: a book in parts or a series with many books. */
data class ArdShow(
    val id: String,
    val title: String,
    val publisher: String,
    val imageUrl: String?,
    val itemCount: Int,
)

/**
 * A book in a list (show, search). [key] is the book's ID ([ardBookKey]); [parts] the number of parts, if
 * known, [durationMs] only if all parts are in the list.
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

/** A book with all parts, as it goes into the library. */
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

    /** Until when the whole book is available: the earliest part with an expiry; `null` = unlimited or unknown. */
    val availableUntil: Long? get() = parts.mapNotNull { it.availableUntil }.minOrNull()
}

/** One part of a book: an audio file. [title] is the chapter's name. */
data class ArdPart(
    val id: String,
    val title: String,
    val audioUrl: String,
    val mimeType: String?,
    val durationMs: Long,
    val availableUntil: Long?,
)

/**
 * Audiobooks and radio plays from ARD Sounds (`books_improvements.md` M9) via the same GraphQL interface as the podcasts
 * ([ArdSoundsApi]). What counts as a book is decided by [ardBookKey]; every error comes as an `IOException`, including a response that
 * reports errors alongside the data (otherwise a book with missing data would count as no longer available).
 */
@Singleton
class ArdSoundsBooks @Inject constructor(private val api: ArdSoundsApi) {

    /** The shows of a category in ARD Sounds' order. */
    suspend fun shows(category: ArdCategory): List<ArdShow> {
        val data = api.query(CATEGORY_QUERY, buildJsonObject { put("id", category.id) }, CategoryData.serializer(), strict = true)
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

    /** The books of the show [showId], newest first. */
    suspend fun showBooks(showId: String): List<ArdBookSummary> {
        val variables = buildJsonObject {
            put("id", showId)
            put("first", SHOW_ITEM_LIMIT)
        }
        val items = api.query(SHOW_ITEMS_QUERY, variables, ShowItemsData.serializer(), strict = true).programSetByCoreId?.items?.nodes.orEmpty()
        return summaries(items.filterNot(::isTrailer))
    }

    /** Books for [term] from the audiobook and radio play categories (the ARD Sounds search finds individual titles). */
    suspend fun search(term: String): List<ArdBookSummary> {
        val variables = buildJsonObject {
            put("query", term)
            put("limit", SEARCH_LIMIT)
        }
        val items = api.query(SEARCH_QUERY, variables, SearchItemsData.serializer(), strict = true).search?.items?.nodes.orEmpty()
        return summaries(items.filter { it.category() != null })
    }

    /** The book [key] with all published parts; `null` if there are none (anymore). */
    suspend fun book(key: String): ArdBook? {
        val parsed = ArdBookKey.parse(key) ?: return null
        val parts = itemsOf(parsed).filter { ardBookKey(it.toKeyInput()) == key }
        return toBook(key, parts)
    }

    /**
     * The new key of the book [key] if ARD Sounds now assigns its titles differently ([book] then no longer finds it).
     * First via the IDs of its titles ([itemIds], [ArdPart.id]); this also catches a group that is now named differently. Without
     * them, or if ARD Sounds no longer lists any of them (republished): a single title or a numbered series without a
     * group now belongs to a group. `null` if the titles no longer exist or are spread across several books.
     */
    suspend fun movedKey(key: String, itemIds: Collection<String> = emptyList()): String? {
        val parsed = ArdBookKey.parse(key) ?: return null
        val byId = if (itemIds.isEmpty()) {
            emptyList()
        } else {
            val ids = buildJsonArray { itemIds.distinct().take(BOOK_PART_LIMIT).forEach { add(it) } }
            fetchItems(buildJsonObject { put("coreId", buildJsonObject { put("in", ids) }) })
        }
        val items = byId.ifEmpty {
            if (parsed is ArdBookKey.Group) return null
            itemsOf(parsed).filter { parsed !is ArdBookKey.Title || numberedTitle(it.title.orEmpty())?.book == parsed.title }
        }
        return items.map { ardBookKey(it.toKeyInput()) }.distinct().singleOrNull()?.takeIf { it != key }
    }

    /** The titles matching the key [parsed] (without checking whether they still belong to this book). */
    private suspend fun itemsOf(parsed: ArdBookKey): List<ItemNode> = when (parsed) {
        is ArdBookKey.Group -> fetchItems(buildJsonObject { put("groupId", buildJsonObject { put("equalTo", parsed.groupId) }) })
        is ArdBookKey.Item -> fetchItems(buildJsonObject { put("coreId", buildJsonObject { put("equalTo", parsed.coreId) }) })
        is ArdBookKey.Title -> fetchItems(
            buildJsonObject {
                put("programSetId", buildJsonObject { put("equalTo", JsonPrimitive(parsed.programSetId)) })
                put("title", buildJsonObject { put("startsWith", parsed.title) })
            },
        )
    }

    private suspend fun fetchItems(filter: JsonObject): List<ItemNode> {
        val variables = buildJsonObject {
            put("filter", filter)
            put("first", BOOK_PART_LIMIT)
        }
        return api.query(BOOK_QUERY, variables, ItemsData.serializer(), strict = true).items?.nodes.orEmpty()
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
        // If the book is the whole show, the show describes the book; otherwise the first part does.
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
        // Without an author in the title ("Späte Rache") the show often names them ("Arthur Conan Doyle: Sherlock Holmes …").
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

/** ID of a book from ARD Sounds in `books.remoteId`. */
internal sealed class ArdBookKey {
    abstract val value: String

    /** The parts of a group (`MULTIPART`, `SERIES`): one work in episodes. */
    data class Group(val groupId: String) : ArdBookKey() {
        override val value get() = "group:$groupId"
    }

    /** Numbered titles of a show without a group ("Tyll (3/8)"). [title] without the number. */
    data class Title(val programSetId: Long, val title: String) : ArdBookKey() {
        override val value get() = "title:$programSetId:$title"
    }

    /** A single title. */
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

/** What [ardBookKey] needs from a title. */
internal data class ArdKeyInput(
    val coreId: String?,
    val title: String,
    val groupId: String?,
    val groupType: String?,
    val programSetId: Long?,
)

/**
 * Which book a title belongs to (`books_improvements.md` M9): groups other than seasons (`SEASON`, where every title is a
 * book) combine their parts; numbered titles of a show without a group ("… (3/8)", "…, Folge 3") via the title without the
 * number; otherwise the title itself is a book.
 */
internal fun ardBookKey(input: ArdKeyInput): String {
    if (input.groupId != null && input.groupType != "SEASON") return ArdBookKey.Group(input.groupId).value
    val numbered = numberedTitle(input.title)
    if (numbered != null && input.programSetId != null) return ArdBookKey.Title(input.programSetId, numbered.book).value
    return ArdBookKey.Item(input.coreId.orEmpty()).value
}

/** A numbered title: the book, the part's number and what follows ("Tyll (3/8): Minnesänger …"). */
internal data class NumberedTitle(val book: String, val number: Int, val label: String?, val rest: String)

/** "Book (3/8)", "Book (3)", "Book, Folge 3", "Book – Teil 3", each with an optional rest after ":" or "–". */
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

/** Author, title and narrator of a title from ARD Sounds; a missing detail is empty. */
internal data class ArdTitle(val author: String, val title: String, val narrator: String)

/**
 * Reads author, title and narrator from the usual notations: `Author: Title`, `Author: "Title" | Gelesen von Narrator`,
 * `"Title" von Author`; the broadcaster's additions (` - WDR 5 Hörbuch`, `| WDR`) are dropped.
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

/** The narrator from "Gelesen von …", "Lesung mit …", "Es liest …"; `null` without such a detail. */
internal fun narratorIn(text: String): String? =
    NARRATOR_PHRASE.find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',', ';')?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }

private val NARRATOR_PHRASE = Regex("""(?:gelesen von|lesung mit|es liest|liest:|sprecherin:|sprecher:)\s+([^\n|.;()]+)""", RegexOption.IGNORE_CASE)
private val SENDER_SUFFIX = Regex(
    """\s*[-–|]\s*(?:(?:WDR|NDR|SWR|BR|HR|MDR|RBB|SR|RB|DLF|Deutschlandfunk|Radio Bremen)(?:\s*\d+)?(?:\s+\S+)?\s*(?:Hörbuch|Hörspiel)?|Hörbuch|Hörspiel)\s*$""",
    RegexOption.IGNORE_CASE,
)
private val BY_AUTHOR = Regex("""^[„"“”]([^"“”„]+)[„"“”]\s+(?:von|nach)\s+(.+)$""")

private fun unquote(text: String): String = text.trim().trim('"', '„', '“', '”', '»', '«').trim()

/** A name has two to five words and no digits ("H. G. Wells", "Emily Brontë"), not "Tatort" or "1001 Nacht". */
private fun looksLikeName(text: String): Boolean {
    val words = text.split(' ').filter { it.isNotBlank() }
    return words.size in 2..5 && text.none { it.isDigit() } && words.all { it.first().isUpperCase() || it in NAME_PARTICLES }
}

private val NAME_PARTICLES = setOf("von", "van", "de", "der", "den", "du", "di", "da", "le", "la", "zu", "y")

/** Parts in the book's order: by number, without a number by publication; unnumbered ones are dropped if there are numbered ones. */
private fun orderParts(parts: List<ItemNode>): List<ItemNode> {
    val numbered = parts.filter { it.partNumber() != null }
    return if (numbered.isEmpty()) {
        parts.sortedBy { it.publishedAt() }
    } else {
        numbered.sortedWith(compareBy<ItemNode> { it.partNumber() }.thenBy { it.publishedAt() }).distinctBy { it.partNumber() }
    }
}

private fun ItemNode.partNumber(): Int? = episodeNumber?.takeIf { groupId != null && group?.type != "SEASON" } ?: numberedTitle(title.orEmpty())?.number

/** Name of the chapter: what follows the number in the title, otherwise "Teil n" (or the title's label, "Folge n"). */
private fun partTitle(item: ItemNode, index: Int, count: Int): String {
    val title = item.title.orEmpty().trim()
    if (count == 1) return title
    val numbered = numberedTitle(title)
    if (numbered != null) return numbered.rest.ifEmpty { "${numbered.label?.replaceFirstChar { it.uppercase() } ?: "Teil"} ${numbered.number}" }
    return title.ifEmpty { "Teil ${index + 1}" }
}

/** The download version, otherwise the streaming one (both regular files), MP3 before AAC. */
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

/** Audiobook before radio play; `null` if the show is in neither of the two categories. */
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
