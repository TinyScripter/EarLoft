package de.kleinabersmart.earloft.core.model

/** Sort order of the books' "All" tab. [AUTHOR]: within that by series and volume, then title. */
enum class BookSort { TITLE, AUTHOR, RECENTLY_ADDED, RECENTLY_PLAYED, DURATION, YEAR }

/** Filter of the "All" tab; [DOWNLOADED] includes books from the device (they're there anyway). */
enum class BookFilter { ALL, NOT_STARTED, STARTED, FINISHED, DOWNLOADED }

/** Sort order of the "Series" tab: by name or last played (any volume of the series). */
enum class SeriesSort { NAME, RECENTLY_PLAYED }

/** Sort order of the "Authors" tab: by name or number of books. */
enum class AuthorSort { NAME, BOOK_COUNT }
