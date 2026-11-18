package de.kleinabersmart.earloft.wear.sync

import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPhoneWantedDto
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneReturnTest {

    private val since = 1_000_000L

    @Test
    fun `an answer while the watch is not waiting changes nothing`() {
        assertEquals(PhoneBackAction.IGNORE, phoneBackAction(waiting = false, since = since, now = since + 3_600_000))
    }

    @Test
    fun `an answer right after the watch started waiting means the phone was never away`() {
        assertEquals(PhoneBackAction.NEVER_AWAY, phoneBackAction(waiting = true, since = since, now = since + 2_000))
        assertEquals(PhoneBackAction.NEVER_AWAY, phoneBackAction(waiting = true, since = since, now = since + PHONE_AWAY_MIN_MS - 1))
    }

    @Test
    fun `an answer after the phone was away starts a sync`() {
        assertEquals(PhoneBackAction.RESYNC, phoneBackAction(waiting = true, since = since, now = since + PHONE_AWAY_MIN_MS))
        assertEquals(PhoneBackAction.RESYNC, phoneBackAction(waiting = true, since = since, now = since + 3 * 3_600_000))
    }

    @Test
    fun `a clock set back on the watch still starts a sync`() {
        assertEquals(PhoneBackAction.RESYNC, phoneBackAction(waiting = true, since = since, now = since - 60_000))
    }

    @Test
    fun `the phone echoes the payload unchanged`() {
        val payload = WearCodec.encode(WearPhoneWantedDto(since))
        assertEquals(WearPhoneWantedDto(since), WearCodec.decode<WearPhoneWantedDto>(payload))
    }
}
