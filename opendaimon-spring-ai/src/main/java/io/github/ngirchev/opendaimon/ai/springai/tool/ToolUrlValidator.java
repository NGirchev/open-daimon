package io.github.ngirchev.opendaimon.ai.springai.tool;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared SSRF guard for built-in web tools.
 */
@Slf4j
final class ToolUrlValidator {

    private static final List<Pattern> BLOCKED_HOST_PATTERNS = List.of(
            Pattern.compile("^localhost$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^.*\\.local$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^metadata\\.google\\.internal$", Pattern.CASE_INSENSITIVE)
    );

    private ToolUrlValidator() {
    }

    static String validatePublicHttpUrl(String url) {
        return validatePublicHttpUrl(url, Set.of(), false);
    }

    static String validatePublicHttpUrl(String url, Set<String> allowedDomains) {
        return validatePublicHttpUrl(url, allowedDomains, false);
    }

    static String validatePublicHttpUrl(String url, boolean allowLoopback) {
        return validatePublicHttpUrl(url, Set.of(), allowLoopback);
    }

    static boolean isUrlSafeToProbe(String url, boolean allowLoopback) {
        return validatePublicHttpUrl(url, Set.of(), allowLoopback) == null;
    }

    private static String validatePublicHttpUrl(String url, Set<String> allowedDomains, boolean allowLoopback) {
        if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return "Invalid URL. Must start with http:// or https://";
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "Invalid URL: no host";
            }

            if (!allowLoopback) {
                for (Pattern pattern : BLOCKED_HOST_PATTERNS) {
                    if (pattern.matcher(host).matches()) {
                        return "Blocked host: " + host;
                    }
                }
            }

            Set<String> domains = allowedDomains != null ? allowedDomains : Set.of();
            if (!domains.isEmpty() && domains.stream().noneMatch(d -> d.equalsIgnoreCase(host))) {
                return "Host not in allowlist: " + host;
            }

            InetAddress address = InetAddress.getByName(host);
            boolean internal = address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || isIpv6UniqueLocalAddress(address)
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress();
            if (internal && !allowLoopback) {
                return "Blocked: private/loopback IP for host " + host;
            }
            return null;
        } catch (UnknownHostException e) {
            return "Cannot resolve host: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Malformed URL: " + e.getMessage();
        }
    }

    private static boolean isIpv6UniqueLocalAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static boolean logAndIsUrlSafeToProbe(String url, boolean allowLoopback) {
        String error = validatePublicHttpUrl(url, Set.of(), allowLoopback);
        if (error != null) {
            log.info("ToolUrlValidator: blocked unsafe url='{}': {}", url, error);
            return false;
        }
        return true;
    }
}
