package de.kleinabersmart.earloft.core.datasource

import java.io.IOException

/** MediaStore returned no cursor (provider not available): the device's library can't be read right now. */
class MediaStoreUnavailableException : IOException("MediaStore query returned no cursor")
