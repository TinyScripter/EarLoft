package de.kleinabersmart.earloft.core.model

/** Reihenfolge des Reiters „Alle“ der Bücher. [AUTHOR]: darin nach Serie und Band, dann Titel. */
enum class BookSort { TITLE, AUTHOR, RECENTLY_ADDED, RECENTLY_PLAYED, DURATION, YEAR }

/** Filter des Reiters „Alle“; [DOWNLOADED] schließt Bücher vom Gerät ein (sie liegen ohnehin da). */
enum class BookFilter { ALL, NOT_STARTED, STARTED, FINISHED, DOWNLOADED }
