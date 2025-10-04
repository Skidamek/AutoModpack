package pl.skidam.automodpack_core.protocol.client.backends;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.client.Client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntConsumer;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class HypertextClient implements Client {
    private final InetSocketAddress address;
    private final Jsons.ModpackContentFields modpackContentFields;

    public HypertextClient(Jsons.ModpackAddresses modpackAddresses, Jsons.ModpackContentFields modpackContentFields, Function<X509Certificate, Boolean> trustedByUserCallback) throws IOException {
        if (modpackAddresses == null || modpackAddresses.isAnyEmpty()) {
            throw new IllegalArgumentException("ModpackAddresses is null or has empty fields");
        }

        this.address = modpackAddresses.hostAddress;
        this.modpackContentFields = modpackContentFields;
    }

    public static HypertextClient tryCreate(Jsons.ModpackAddresses modpackAddresses, Jsons.ModpackContentFields modpackContentFields, Function<X509Certificate, Boolean> trustedByUserCallback) {
        try {
            return new HypertextClient(modpackAddresses, modpackContentFields, trustedByUserCallback);
        } catch (IOException e) {
            LOGGER.error("Failed to create a hypertext client. Error: {}", e.getMessage());
            return null;
        }
    }

    private String urlFromHashBytes(byte[] fileHash) {
        String file = "/" + GlobalVariables.hostModpackContentFile.getFileName().toString();
        if (Arrays.equals(fileHash, new byte[0]) || fileHash == null || modpackContentFields.list == null) return address.getHostString() + file;
        for (var modpackContentItem : modpackContentFields.list) {
            if (modpackContentItem.sha1.equals(new String(fileHash))) {
                file = modpackContentItem.file;
                break;
            }
        }
        return address.getHostString() + file;
    }

    @Override
    public CompletableFuture<Path> downloadFile(byte[] fileHash, Path destination, IntConsumer chunkCallback) {
        final String url = urlFromHashBytes(fileHash);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .header("Accept", "*/*")
                    .build();
        } catch (IllegalArgumentException iae) {
            CompletableFuture<Path> failed = new CompletableFuture<>();
            failed.completeExceptionally(iae);
            return failed;
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<Path> result = httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenComposeAsync(resp -> {
                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        return CompletableFuture.failedFuture(new IOException("HTTP error: " + resp.statusCode() + " for URL: " + url));
                    }
                    return CompletableFuture.supplyAsync(() -> {
                        try (InputStream in = resp.body()) {
                            Files.createDirectories(destination.getParent());
                            try (var out = Files.newOutputStream(destination)) {
                                byte[] buffer = new byte[8 * 1024];
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, read);
                                    if (chunkCallback != null) chunkCallback.accept(read);
                                }
                            }
                            return destination;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                })
                .exceptionallyCompose(CompletableFuture::failedFuture);

        return result;
    }

    @Override
    public CompletableFuture<Path> requestRefresh(byte[][] fileHashes, Path destination) {
        return CompletableFuture.failedFuture(new IOException("Not implemented"));
    }

    @Override
    public void close() {
        // No resources to close
    }
}
