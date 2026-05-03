package io.github.ngirchev.opendaimon.ai.springai.config;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Collections;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the SSL helpers wired into {@link SpringAIAutoConfig#webToolsWebClient}.
 * Focus: {@code buildWebToolsSslContext} never throws and always returns a non-null, usable
 * {@link SslContext}, regardless of platform (macOS with Apple JSSE vs. non-macOS).
 *
 * <p>No real HTTPS traffic is generated — assertions are strictly on configuration objects.
 */
class SpringAIAutoConfigSslTest {

    @Test
    void shouldReturnNonNullSslContextUnderNormalJdk() {
        SslContext sslContext = SpringAIAutoConfig.buildWebToolsSslContext(false);

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void shouldReturnNonNullSslContextWhenIncludingKeychainOnAnyPlatform() {
        // includeKeychain = true exercises the silent-degradation path on non-macOS hosts
        // (Keychain load throws, method logs WARN, merge is skipped); on macOS it actually
        // imports keychain entries. Either way the returned SslContext must be non-null.
        SslContext sslContext = SpringAIAutoConfig.buildWebToolsSslContext(true);

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void shouldLoadJdkTrustStoreWithAtLeastOneAcceptedIssuer() throws Exception {
        KeyStore jdkStore = SpringAIAutoConfig.loadJdkTrustStore();

        assertThat(jdkStore).isNotNull();
        assertThat(jdkStore.size()).isGreaterThan(0);
    }

    @Test
    void shouldHaveMergedStoreWithAtLeastAsManyIssuersAsJdkCacertsAlone() throws Exception {
        KeyStore jdkOnly = SpringAIAutoConfig.loadJdkTrustStore();
        int jdkIssuers = acceptedIssuerCount(jdkOnly);

        KeyStore merged = SpringAIAutoConfig.loadJdkTrustStore();
        SpringAIAutoConfig.mergeMacKeychainInto(merged);
        int mergedIssuers = acceptedIssuerCount(merged);

        // Merge either no-ops (non-macOS / Apple provider absent) or adds entries — never removes.
        assertThat(mergedIssuers).isGreaterThanOrEqualTo(jdkIssuers);
    }

    @Test
    void shouldSkipKeychainMergeSilentlyWhenLoadThrows() throws Exception {
        // mergeMacKeychainInto must never propagate — simulate a hostile target by passing
        // a KeyStore that has not been initialised. Any internal failure caused by an
        // uninitialised target must also be swallowed: the method is best-effort by contract.
        KeyStore uninitialised = KeyStore.getInstance(KeyStore.getDefaultType());
        // Intentionally do NOT call load() — setCertificateEntry will throw if executed against
        // an uninitialised store on certain providers.

        // Must not throw regardless of what the keychain side does.
        SpringAIAutoConfig.mergeMacKeychainInto(uninitialised);
    }

    @Test
    void shouldFallBackToWorkingSslContextWhenBuiltWithoutKeychain() {
        // Explicitly exercises the "JDK cacerts only" branch (step 1 succeeds, step 2 skipped).
        SslContext sslContext = SpringAIAutoConfig.buildWebToolsSslContext(false);

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void shouldReflectAppleProviderPresenceConsistentlyWithSecurityLookup() {
        // The helper is a thin wrapper over Security.getProvider("Apple"); assert it agrees
        // with a direct lookup so tests on macOS and Linux both validate the boolean contract.
        Provider apple = Security.getProvider("Apple");
        boolean expected = apple != null;

        assertThat(SpringAIAutoConfig.isAppleProviderAvailable()).isEqualTo(expected);
    }

    @Test
    void shouldProduceTrustManagerWithAcceptedIssuersWhenInitialisedFromMergedStore() throws Exception {
        KeyStore merged = SpringAIAutoConfig.loadJdkTrustStore();
        SpringAIAutoConfig.mergeMacKeychainInto(merged);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(merged);

        X509TrustManager x509 = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                x509 = x;
                break;
            }
        }

        assertThat(x509).as("merged TrustManagerFactory must expose an X509TrustManager").isNotNull();
        assertThat(x509.getAcceptedIssuers()).isNotEmpty();
    }

    /**
     * Counts accepted issuers exposed by a {@link TrustManagerFactory} initialised from
     * {@code keyStore}. Stable across JDKs — {@code KeyStore.size()} also works but counts
     * private-key entries too; we want the "trust anchors" view the SSL stack sees.
     */
    private static int acceptedIssuerCount(KeyStore keyStore) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x.getAcceptedIssuers().length;
            }
        }
        return 0;
    }

    /**
     * Explicit listing of {@code java.home} so that {@link SpringAIAutoConfig#loadJdkTrustStore()}
     * is known to operate against a real cacerts file during the test run. Fails fast with a
     * descriptive message if the surrounding environment is unexpectedly stripped of cacerts.
     */
    @Test
    void shouldSeeARealCacertsFileInJavaHome() throws Exception {
        String javaHome = System.getProperty("java.home");
        assertThat(javaHome).as("java.home must be set by the JVM").isNotBlank();

        Path cacerts = Path.of(javaHome, "lib", "security", "cacerts");
        assertThat(Files.exists(cacerts))
                .as("Expected JDK cacerts at %s", cacerts)
                .isTrue();

        // Smoke-load it through the real code path — must not throw.
        try (InputStream in = Files.newInputStream(cacerts)) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(in, "changeit".toCharArray());
            assertThat(ks.size()).isGreaterThan(0);
            // Enumerate to catch provider-specific lazy-init bugs.
            Enumeration<String> aliases = ks.aliases();
            assertThat(Collections.list(aliases)).isNotEmpty();
        }
    }
}
