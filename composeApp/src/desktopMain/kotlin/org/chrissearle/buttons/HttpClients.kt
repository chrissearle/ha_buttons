package org.chrissearle.buttons

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import org.chrissearle.buttons.config.ConfigException
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a trust manager that trusts only the CA certificate(s) in [pemFile].
 * Hostname verification is still performed by the TLS engine.
 */
fun loadTrustManager(pemFile: File): X509TrustManager {
    if (!pemFile.isFile) throw ConfigException("ca_cert file not found: ${pemFile.absolutePath}")

    val certificates = try {
        pemFile.inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).filterIsInstance<X509Certificate>()
        }
    } catch (e: Exception) {
        throw ConfigException("Could not read ca_cert ${pemFile.absolutePath}: ${e.message}", e)
    }
    if (certificates.isEmpty()) throw ConfigException("No certificates found in ca_cert ${pemFile.absolutePath}")

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
    }

    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()
}

/** Resolves `ca_cert`: supports `~/` and paths relative to the config file's directory. */
fun resolveCaCertPath(caCert: String, configFile: File): File = when {
    caCert.startsWith("~/") -> File(System.getProperty("user.home"), caCert.removePrefix("~/"))
    File(caCert).isAbsolute -> File(caCert)
    else -> File(configFile.absoluteFile.parentFile, caCert)
}

/** HTTP client with WebSocket support. [trustManager] null = system default trust store. */
fun createHttpClient(trustManager: X509TrustManager?): HttpClient = HttpClient(CIO) {
    engine {
        if (trustManager != null) {
            https { this.trustManager = trustManager }
        }
    }
    install(WebSockets) {
        pingIntervalMillis = 30_000
    }
}
