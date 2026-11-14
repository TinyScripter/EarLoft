package de.kleinabersmart.earloft.core.model

/** Reihenfolge des Reiters „Alle“ der Bücher. [AUTHOR]: darin nach Serie und Band, dann Titel. */
enum class BookSort { TITLE, AUTHOR, RECENTLY_ADDED, RECENTLY_PLAYED, DURATION, YEAR }

/** Filter des Reiters „Alle“; [DOWNLOADED] schließt Bücher vom Gerät ein (sie liegen ohnehin da). */
enum class BookFilter { ALL, NOT_STARTED, STARTED, FINISHED, DOWNLOADED }

/** Reihenfolge des Reiters „Serien“: nach Name oder zuletzt gehört (irgendein Band der Serie). */
enum class SeriesSort { NAME, RECENTLY_PLAYED }

/** Reihenfolge des Reiters „Autoren“: nach Name oder Anzahl der Bücher. */
enum class AuthorSort { NAME, BOOK_COUNT }
