package io.yunuservices.features.core.image;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

public final class RemoteImageSourcePolicy {
    private RemoteImageSourcePolicy() {
    }

    public static URL resolveRemoteUrl(String source, boolean allowUrlSources, boolean allowPrivateAddressUrls)
        throws IOException {
        if (!allowUrlSources) {
            throw new IOException("URL sources are disabled in settings.yml.");
        }

        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed URL: " + source, e);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("Only http/https URLs are allowed.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("URL must include a valid host.");
        }

        URL url;
        try {
            url = uri.toURL();
        } catch (MalformedURLException e) {
            throw new IOException("Malformed URL: " + source, e);
        }

        validateRemoteHost(uri.getHost(), allowPrivateAddressUrls);
        return url;
    }

    public static Path resolveImportFile(Path importDir, String source) throws IOException {
        try {
            Path root = importDir.toAbsolutePath().normalize();
            Path file = root.resolve(source).normalize();
            if (!file.startsWith(root)) {
                throw new IOException("File path escapes import directory.");
            }
            return file;
        } catch (InvalidPathException e) {
            throw new IOException("Invalid file path: " + source, e);
        }
    }

    private static void validateRemoteHost(String host, boolean allowPrivateAddressUrls) throws IOException {
        resolveAddresses(host, allowPrivateAddressUrls);
    }

    public static InetAddress[] resolveAddresses(String host, boolean allowPrivateAddressUrls) throws IOException {
        if (allowPrivateAddressUrls) {
            return InetAddress.getAllByName(host);
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
            throw new IOException("Private/local URLs are not allowed.");
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new IOException("Private/local URLs are not allowed.");
            }
        }
        return addresses;
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet6Address inet6Address) {
            byte[] bytes = inet6Address.getAddress();
            return bytes.length > 0 && (bytes[0] & 0xFE) == 0xFC;
        }

        return false;
    }
}
