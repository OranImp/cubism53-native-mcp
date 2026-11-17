package cubism53.mcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Check the unauthenticated read budget on a dedicated loopback socket. */
public final class NativeWireCheck {
    private static final String TOKEN = "fixture-token-with-at-least-24-characters";

    public static void main(String[] args) throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        try (var listener = new ServerSocket(0, 2, loopback)) {
            var oversized = check(listener, 2000);
            try (var socket = new Socket(loopback, listener.getLocalPort())) {
                socket.getOutputStream().write(new byte[257]);
                var failure = oversized.get(3, TimeUnit.SECONDS);
                if (!(failure instanceof IOException) || !failure.getMessage().contains("256 bytes"))
                    throw new AssertionError("Expected oversized pre-auth frame rejection", failure);
            }

            var drip = check(listener, 500);
            try (var socket = new Socket(loopback, listener.getLocalPort())) {
                var output = socket.getOutputStream();
                for (int run = 0; run < 16 && !drip.isDone(); run++) {
                    output.write('a');
                    output.flush();
                    Thread.sleep(50);
                }
                var failure = drip.get(100, TimeUnit.MILLISECONDS);
                if (!(failure instanceof SocketTimeoutException))
                    throw new AssertionError("Expected an absolute read deadline despite incoming bytes", failure);
            }
        }
        System.out.println("Native authentication frame limit and absolute read deadline verified");
    }

    private static FutureTask<Exception> check(ServerSocket listener, int millis) {
        var result = new FutureTask<Exception>(() -> {
            try (var socket = listener.accept()) {
                NativeBridgeWire.request(socket, TOKEN, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
            } catch (Exception error) {
                return error;
            }
            throw new AssertionError("Unauthenticated request was accepted");
        });
        var thread = new Thread(result, "wire-test");
        thread.setDaemon(true);
        thread.start();
        return result;
    }
}
