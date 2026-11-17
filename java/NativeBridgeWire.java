package cubism53.mcp;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/** Prove possession of the profile secret before accepting a request body. */
final class NativeBridgeWire {
    private static final String HELLO = "CUBISM53-MCP/" + NativeBridgeBuild.PROTOCOL;
    private static final int HANDSHAKE_LIMIT = 256;
    private static final int REQUEST_LIMIT = 32 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 64;
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern HEX_256 = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    @SuppressWarnings("unchecked")
    static Map<String,Object> request(Socket socket, String token, long deadline)
            throws IOException, GeneralSecurityException, ParseException {
        var reader = new Frames(socket, deadline);
        String clientNonce = handshakeValue(reader.read(HANDSHAKE_LIMIT));
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        String serverNonce = HEX.formatHex(nonce);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String proof = HEX.formatHex(proof(mac, "server", clientNonce, serverNonce));
        var output = socket.getOutputStream();
        output.write((HELLO + " " + serverNonce + " " + proof + "\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        byte[] clientProof = HEX.parseHex(handshakeValue(reader.read(HANDSHAKE_LIMIT)));
        if (!MessageDigest.isEqual(proof(mac, "client", clientNonce, serverNonce), clientProof))
            throw new SecurityException("Unauthorized local client");

        String body = reader.read(REQUEST_LIMIT);
        checkDepth(body);
        if (!(new JSONParser().parse(body) instanceof Map<?,?> request))
            throw new IllegalArgumentException("Bridge request must be a JSON object");
        return (Map<String,Object>) request;
    }

    private static String handshakeValue(String line) {
        String prefix = HELLO + " ";
        if (line.startsWith(prefix)) {
            String value = line.substring(prefix.length());
            if (HEX_256.matcher(value).matches()) return value;
        }
        throw new SecurityException("Unauthorized local client");
    }

    private static byte[] proof(Mac mac, String role, String clientNonce, String serverNonce) {
        return mac.doFinal((HELLO + " " + role + " " + clientNonce + " " + serverNonce)
            .getBytes(StandardCharsets.US_ASCII));
    }

    private static void checkDepth(String body) {
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == '{' || ch == '[') {
                if (++depth > MAX_JSON_DEPTH)
                    throw new IllegalArgumentException("Bridge request nesting exceeds " + MAX_JSON_DEPTH);
            } else if ((ch == '}' || ch == ']') && --depth < 0) {
                throw new IllegalArgumentException("Invalid bridge JSON structure");
            }
        }
    }

    private static final class Frames {
        private final Socket socket;
        private final InputStream input;
        private final long deadline;
        private final byte[] buffer = new byte[8192];
        private int offset;
        private int end;

        Frames(Socket socket, long deadline) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.deadline = deadline;
        }

        String read(int limit) throws IOException {
            var frame = new ByteArrayOutputStream();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("Bridge request read deadline exceeded");
                if (offset == end) {
                    socket.setSoTimeout((int) (1 + TimeUnit.NANOSECONDS.toMillis(remaining - 1)));
                    offset = 0;
                    end = input.read(buffer);
                    if (end == -1) throw new EOFException("Incomplete bridge frame");
                }
                int start = offset;
                while (offset < end && buffer[offset] != '\n') offset++;
                int bytes = offset - start;
                if (bytes > limit - frame.size()) throw new IOException("Bridge frame exceeds " + limit + " bytes");
                frame.write(buffer, start, bytes);
                if (offset < end) {
                    offset++;
                    byte[] encoded = frame.toByteArray();
                    int length = encoded.length;
                    if (length > 0 && encoded[length - 1] == '\r') length--;
                    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded, 0, length)).toString();
                }
            }
        }
    }
}
