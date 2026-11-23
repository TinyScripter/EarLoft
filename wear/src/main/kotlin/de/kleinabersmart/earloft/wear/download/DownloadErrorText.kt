package de.kleinabersmart.earloft.wear.download

import androidx.annotation.StringRes
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import de.kleinabersmart.earloft.wear.R

/** The text of the error on the watch; the phone has its own (review2 Z20). */
@get:StringRes
val WearDownloadError.textRes: Int
    get() = when (this) {
        WearDownloadError.SERVER_VIA_PHONE -> R.string.download_error_server_via_phone
        WearDownloadError.SERVER_REFUSED_VIA_PHONE -> R.string.download_error_server_refused_via_phone
        WearDownloadError.SERVER_NO_PHONE_COPY -> R.string.download_error_server_no_phone_copy
        WearDownloadError.PHONE_NO_COPY -> R.string.download_error_phone_no_copy
        WearDownloadError.STORAGE_FULL -> R.string.download_error_storage_full
        WearDownloadError.PHONE_UNREACHABLE -> R.string.download_error_phone_unreachable
    }
