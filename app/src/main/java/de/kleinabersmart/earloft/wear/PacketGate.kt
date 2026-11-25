package de.kleinabersmart.earloft.wear

/**
 * Every DataItem the watch leaves is imported by one path at a time and only while it still exists. Two paths see the same
 * items: [PhoneWearListenerService.onDataChanged] and [WatchPackets.catchUp] at start. After a long time without the phone
 * hundreds of packets arrive at once, and the importers remember only the last ones: a path that comes to an item the other
 * has long since imported and deleted must not import it a second time, or its listening time and scrobbles count twice
 * (review4 T7).
 */
internal class PacketGate {
    private val handling = HashSet<String>()
    private val lock = Any()

    /**
     * Runs [block] for the item [key] unless another path is handling it right now, or [stillThere] says it has been deleted
     * (by that other path). [block] must delete the item at its end; the claim is held until then.
     */
    suspend fun once(key: String, stillThere: suspend () -> Boolean, block: suspend () -> Unit) {
        synchronized(lock) { if (!handling.add(key)) return }
        try {
            if (stillThere()) block()
        } finally {
            synchronized(lock) { handling.remove(key) }
        }
    }
}
