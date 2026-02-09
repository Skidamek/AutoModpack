package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;

import static pl.skidam.automodpack_core.Constants.AM_VERSION;

public class HttpFileDownloader {

    private static final Logger LOGGER = LogManager.getLogger();

    // Shared Client for HTTP/2 Multiplexing and Connection Pooling
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2) // Auto-negotiate HTTP/2
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newCachedThreadPool()) // Async handler
            .build();

    /**
     * Downloads a file from a URL to a target path using HTTP/2 if available.
     * Blocks the calling thread (designed for use in Worker Threads).
     *
     * @param url            The source URL.
     * @param target         The destination file path.
     * @param progressAction A callback to report bytes read (for bandwidth tracking).
     * @throws IOException          If network or IO fails.
     * @throws InterruptedException If the download is cancelled.
     */
    public void download(String url, Path target, IntConsumer progressAction) throws IOException, InterruptedException {
        SmartFileUtils.createParentDirs(target);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "github/skidamek/automodpack/" + AM_VERSION)
                .header("Accept-Encoding", "gzip")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

            LOGGER.info("HTTPS Download {}: Source={} Protocol={} Status={}", target.getFileName(), url, response.version(), response.statusCode());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP Error " + response.statusCode() + " for " + url);
            }

            boolean isGzip = "gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""));

            try (InputStream rawIn = response.body();
                 InputStream in = isGzip ? new GZIPInputStream(rawIn) : rawIn;
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target.toFile()), 64 * 1024)) {

                byte[] buffer = new byte[NetUtils.DEFAULT_CHUNK_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buffer, 0, bytesRead);
                    
                    if (progressAction != null) {
                        progressAction.accept(bytesRead);
                    }
                }
            }

        } catch (InterruptedException e) {
            throw e; // Rethrow to handle cancellation
        } catch (IOException e) {
            throw e; // Rethrow IO errors
        } catch (Exception e) {
            // Wrap unexpected HTTP client errors
            throw new IOException("HTTP Client Protocol Error", e);
        }
    }
}