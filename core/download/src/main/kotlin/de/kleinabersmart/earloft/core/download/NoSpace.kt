package de.kleinabersmart.earloft.core.download

import android.system.ErrnoException
import android.system.OsConstants

/** The storage is full (`ENOSPC`), somewhere in the chain of causes. */
fun Throwable.isNoSpace(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it.message?.contains("ENOSPC") == true || (it is ErrnoException && it.errno == OsConstants.ENOSPC) }
