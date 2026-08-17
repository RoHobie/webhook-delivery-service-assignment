package com.webhook.delivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Service
public class UrlValidationService {

    private final boolean allowInternalUrls;

    public UrlValidationService(@Value("${app.allow-internal-urls:false}") boolean allowInternalUrls) {
        this.allowInternalUrls = allowInternalUrls;
    }

    public void validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed URL: " + urlString);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("URL scheme must be http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid host");
        }

        if (!allowInternalUrls) {
            if (isInternalHost(host)) {
                throw new IllegalArgumentException("Internal / local IP addresses and hostnames are forbidden: " + host);
            }
        }
    }

    private boolean isInternalHost(String host) {
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost") || host.equalsIgnoreCase("127.0.0.1") || host.equals("::1")) {
            return true;
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return true;
            }

            // Check private IPv4 ranges manually in case isSiteLocalAddress misses some
            byte[] ip = address.getAddress();
            if (ip.length == 4) {
                int b0 = ip[0] & 0xFF;
                int b1 = ip[1] & 0xFF;

                // 10.0.0.0/8
                if (b0 == 10) return true;
                // 172.16.0.0/12
                if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
                // 192.168.0.0/16
                if (b0 == 192 && b1 == 168) return true;
                // 169.254.0.0/16 (Link local)
                if (b0 == 169 && b1 == 254) return true;
                // 0.0.0.0/8
                if (b0 == 0) return true;
            }
        } catch (UnknownHostException e) {
            // Cannot resolve host - allowed to proceed or fail depending on network, but host validation passed scheme
        }

        return false;
    }
}
