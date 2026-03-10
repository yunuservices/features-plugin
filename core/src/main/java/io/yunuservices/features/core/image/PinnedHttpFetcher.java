package io.yunuservices.features.core.image;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class PinnedHttpFetcher {
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_HEADER_LINE_BYTES = 8_192;

    private PinnedHttpFetcher() {
    }

    static byte[] fetch(
        URL url,
        boolean allowPrivateAddressUrls,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maxBytes
    ) throws IOException {
        URI current = toUri(url);
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            RemoteImageSourcePolicy.resolveRemoteUrl(
                current.toString(),
                true,
                allowPrivateAddressUrls
            );

            HttpResponse response = request(
                current,
                allowPrivateAddressUrls,
                connectTimeoutMillis,
                readTimeoutMillis,
                maxBytes
            );

            if (isRedirect(response.statusCode())) {
                String location = response.headers().get("location");
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect response is missing Location header: " + current);
                }
                current = current.resolve(location);
                continue;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Remote server returned HTTP " + response.statusCode() + ": " + current);
            }
            return response.body();
        }
        throw new IOException("Too many redirects: " + url);
    }

    private static HttpResponse request(
        URI uri,
        boolean allowPrivateAddressUrls,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        int maxBytes
    ) throws IOException {
        InetAddress[] addresses = RemoteImageSourcePolicy.resolveAddresses(uri.getHost(), allowPrivateAddressUrls);
        if (addresses.length == 0) {
            throw new IOException("Could not resolve host: " + uri.getHost());
        }

        InetAddress address = addresses[0];
        try (Socket socket = openSocket(uri, address, connectTimeoutMillis, readTimeoutMillis)) {
            writeRequest(socket, uri);

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(input);
            if (statusLine == null || statusLine.isBlank()) {
                throw new IOException("Remote server returned an empty response: " + uri);
            }

            int statusCode = parseStatusCode(statusLine, uri);
            Map<String, String> headers = readHeaders(input);
            byte[] body = readBody(input, headers, maxBytes, uri);
            return new HttpResponse(statusCode, headers, body);
        }
    }

    private static Socket openSocket(
        URI uri,
        InetAddress address,
        int connectTimeoutMillis,
        int readTimeoutMillis
    ) throws IOException {
        int port = port(uri);
        Socket rawSocket = new Socket();
        try {
            rawSocket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
            rawSocket.setSoTimeout(readTimeoutMillis);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return rawSocket;
            }

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(rawSocket, uri.getHost(), port, true);
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(parameters);
            sslSocket.setSoTimeout(readTimeoutMillis);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (IOException e) {
            rawSocket.close();
            throw e;
        }
    }

    private static void writeRequest(Socket socket, URI uri) throws IOException {
        Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
        writer.write("GET ");
        writer.write(requestTarget(uri));
        writer.write(" HTTP/1.1\r\n");
        writer.write("Host: ");
        writer.write(hostHeader(uri));
        writer.write("\r\n");
        writer.write("User-Agent: Features/1.0.0\r\n");
        writer.write("Accept: image/*,*/*;q=0.8\r\n");
        writer.write("Accept-Encoding: identity\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
    }

    private static byte[] readBody(
        BufferedInputStream input,
        Map<String, String> headers,
        int maxBytes,
        URI uri
    ) throws IOException {
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            long parsedContentLength = parseContentLength(contentLength, uri);
            if (parsedContentLength > maxBytes) {
                throw new IOException("Remote image exceeds " + maxBytes + " bytes: " + uri.toURL());
            }
        }

        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunkedBody(input, maxBytes, uri);
        }
        if (contentLength != null) {
            return readFixedLengthBody(input, parseContentLength(contentLength, uri), maxBytes, uri);
        }
        return readToEnd(input, maxBytes, uri);
    }

    private static byte[] readFixedLengthBody(
        BufferedInputStream input,
        long contentLength,
        int maxBytes,
        URI uri
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(contentLength, 8192));
        copy(input, output, contentLength, maxBytes, uri);
        return output.toByteArray();
    }

    private static byte[] readChunkedBody(BufferedInputStream input, int maxBytes, URI uri) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String chunkLine = readLine(input);
            if (chunkLine == null) {
                throw new IOException("Unexpected end of stream while reading chunk size: " + uri);
            }

            String chunkSizeToken = chunkLine.split(";", 2)[0].trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(chunkSizeToken, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size '" + chunkLine + "': " + uri, e);
            }

            if (chunkSize == 0) {
                while (true) {
                    String trailer = readLine(input);
                    if (trailer == null || trailer.isEmpty()) {
                        return output.toByteArray();
                    }
                }
            }

            copy(input, output, chunkSize, maxBytes, uri);
            expectLineEnd(input, uri);
        }
    }

    private static byte[] readToEnd(BufferedInputStream input, int maxBytes, URI uri) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Remote image exceeds " + maxBytes + " bytes: " + uri.toURL());
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copy(
        BufferedInputStream input,
        ByteArrayOutputStream output,
        long expectedBytes,
        int maxBytes,
        URI uri
    ) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = expectedBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected end of stream: " + uri);
            }
            if (output.size() + read > maxBytes) {
                throw new IOException("Remote image exceeds " + maxBytes + " bytes: " + uri.toURL());
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void expectLineEnd(BufferedInputStream input, URI uri) throws IOException {
        int first = input.read();
        int second = input.read();
        if (first != '\r' || second != '\n') {
            throw new IOException("Malformed chunked response body: " + uri);
        }
    }

    private static Map<String, String> readHeaders(BufferedInputStream input) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null || line.isEmpty()) {
                return headers;
            }

            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Malformed response header: " + line);
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            headers.put(name, value);
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (output.size() == 0) {
                    return null;
                }
                break;
            }
            if (value == '\n') {
                break;
            }
            if (output.size() >= MAX_HEADER_LINE_BYTES) {
                throw new IOException("Remote response header line is too long.");
            }
            if (value != '\r') {
                output.write(value);
            }
        }
        return output.toString(StandardCharsets.ISO_8859_1);
    }

    private static int parseStatusCode(String statusLine, URI uri) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed HTTP status line: " + statusLine + " (" + uri + ")");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed HTTP status line: " + statusLine + " (" + uri + ")", e);
        }
    }

    private static long parseContentLength(String contentLength, URI uri) throws IOException {
        try {
            long value = Long.parseLong(contentLength.trim());
            if (value < 0) {
                throw new IOException("Negative Content-Length: " + uri);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length '" + contentLength + "': " + uri, e);
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301
            || statusCode == 302
            || statusCode == 303
            || statusCode == 307
            || statusCode == 308;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String requestTarget(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return path;
        }
        return path + "?" + uri.getRawQuery();
    }

    private static String hostHeader(URI uri) {
        String host = uri.getHost();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }

        int port = port(uri);
        boolean defaultPort = ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
            || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        if (defaultPort) {
            return host;
        }
        return host + ":" + port;
    }

    private static URI toUri(URL url) throws IOException {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Malformed URL: " + url, e);
        }
    }

    private record HttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
    }
}
