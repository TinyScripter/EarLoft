package de.kleinabersmart.earloft.core.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinSecretTest {

    @Test
    fun `user id and token survive encoding, broken values are rejected`() {
        val secret = JellyfinSecret("c18571738eb1466db88e3463d6d1e9e3", "a1b2:c3")

        assertEquals(secret, JellyfinSecret.decode(secret.encode()))
        assertNull(JellyfinSecret.decode("nur-ein-passwort"))
        assertNull(JellyfinSecret.decode(":token"))
        assertFalse("a1b2" in secret.toString())
    }
}
