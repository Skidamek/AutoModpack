package pl.skidam.automodpack_core.netty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private final int status;
    private final Map<String, String> headers = new HashMap<>();

    HttpResponse(int status) {
        this.status = status;
    }

    void addHeader(String name, String value) {
        headers.put(name, value);
    }

    String getResponseMessage() {
        StringBuilder response = new StringBuilder();

        response.append("HTTP/1.1 ").append(status).append("\r\n");

        for (Map.Entry<String, String> header : headers.entrySet()) {
            response.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }

        response.append("\r\n");

        return response.toString();
    }

    // Not sure if its really needed
    void setContentTypeHeader(Path file) throws IOException {
        String contentType = Files.probeContentType(file);
        addHeader("Content-Type", contentType);
    }
}