package org.chrissearle.buttons

import org.chrissearle.buttons.config.ConfigException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrustManagerTest {
    private fun resource(name: String) = File(javaClass.classLoader.getResource(name)!!.toURI())

    @Test
    fun trustsOnlyTheConfiguredCa() {
        val tm = loadTrustManager(resource("test-ca.pem"))
        val issuers = tm.acceptedIssuers.map { it.subjectX500Principal.name }
        assertEquals(listOf("CN=Buttons Test CA"), issuers)
    }

    @Test
    fun missingFileIsAConfigError() {
        assertFailsWith<ConfigException> { loadTrustManager(File("/does/not/exist.pem")) }
    }

    @Test
    fun garbageFileIsAConfigError() {
        val f = File.createTempFile("bad", ".pem").apply { writeText("not a cert"); deleteOnExit() }
        assertFailsWith<ConfigException> { loadTrustManager(f) }
    }

    @Test
    fun resolvesRelativeCaPathAgainstConfigDir() {
        val config = File("/etc/buttons/config.yaml")
        assertEquals(File("/etc/buttons/ca.pem"), resolveCaCertPath("ca.pem", config))
        assertEquals(File("/abs/ca.pem"), resolveCaCertPath("/abs/ca.pem", config))
        assertEquals(File(System.getProperty("user.home"), "ca.pem"), resolveCaCertPath("~/ca.pem", config))
    }
}
