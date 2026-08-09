package pl.skidam.automodpack_core.utils;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void modrinthHashLookupDoesNotUseCurseForgeValidationOrKey() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> requestHeaders = executor.submit(() -> readRequestAndRespond(server));
                String requestUrl = "http://127.0.0.1:" + server.getLocalPort() + "/version_files";

                JsonObject response = Json.fromModrinthUrl(requestUrl, List.of("0123456789012345678901234567890123456789"));

                assertNotNull(response);
                assertFalse(requestHeaders.get(2, TimeUnit.SECONDS).toLowerCase().contains("x-api-key"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void curseForgeLookupRejectsUntrustedEndpointsBeforeSendingTheKey() {
        assertThrows(IOException.class, () -> Json.fromCurseForgeUrl(
                "http://127.0.0.1/fingerprints",
                List.of("1234567890")
        ));
    }

    private static String readRequestAndRespond(ServerSocket server) throws Exception {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {
            StringBuilder headers = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append('\n');
            }

            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + response.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(response);
            output.flush();
            return headers.toString();
        }
    }
}
