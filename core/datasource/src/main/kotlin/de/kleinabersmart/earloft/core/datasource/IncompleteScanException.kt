package de.kleinabersmart.earloft.core.datasource

import java.io.IOException

/**
 * A full scan stopped because the library [libraryName] changed on the server while it was read, or the server
 * delivered fewer songs than it announced. The sync deletes nothing in that case; the next attempt usually works.
 * The UI shows its own text for it, [message] is only for the log.
 */
class IncompleteScanException(val libraryName: String, message: String) : IOException(message)
