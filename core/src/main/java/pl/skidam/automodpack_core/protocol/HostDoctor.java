package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.net.InetSocketAddress;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static pl.skidam.automodpack_core.Constants.*;

/**
 * Self-diagnosis for the modpack host: walks through the config, TLS setup and - most
 * importantly - dials the host exactly like a client would (magic packets + TLS handshake
 * + certificate check) and reports a checklist with the concrete config fix per failure.
 */
public class HostDoctor {

    public enum Status { OK, INFO, WARN, FAIL }

    public record CheckResult(Status status, String name, String detail) {}

    // Logs the checklist - used when hosting fails at boot, where no command source exists.
    // Pass minecraftPort -1 when unknown.
    public static void logReport(int minecraftPort) {
        for (CheckResult result : run(minecraftPort)) {
            String line = "[" + result.status() + "] " + result.name() + (result.detail().isEmpty() ? "" : " - " + result.detail());
            switch (result.status()) {
                case FAIL -> LOGGER.error(line);
                case WARN -> LOGGER.warn(line);
                default -> LOGGER.info(line);
            }
        }
    }

    public static List<CheckResult> run(int minecraftPort) {
        List<CheckResult> results = new ArrayList<>();

        if (!serverConfig.modpackHost) {
            results.add(new CheckResult(Status.FAIL, "Hosting enabled",
                    "modpackHost is false in " + serverConfigFile + " - set it to true and restart"));
            return results;
        }
        results.add(new CheckResult(Status.OK, "Hosting enabled", "modpackHost is true"));

        boolean running = hostServer.isRunning();
        results.add(running
                ? new CheckResult(Status.OK, "Host server running", "")
                : new CheckResult(Status.FAIL, "Host server running",
                        "run '/automodpack host start' and check the startup log for bind errors (is the port already in use?)"));

        var content = ConfigTools.loadModpackContent(hostModpackContentFile);
        if (content == null || content.list == null || content.list.isEmpty()) {
            results.add(new CheckResult(Status.FAIL, "Modpack generated",
                    "no modpack content found - run '/automodpack generate'"));
        } else {
            results.add(new CheckResult(Status.OK, "Modpack generated",
                    "'" + content.modpackName + "' with " + content.list.size() + " files"));
        }

        checkTls(results);
        checkPorts(results, minecraftPort);

        if (running) {
            selfDial(results, minecraftPort);
        } else {
            results.add(new CheckResult(Status.INFO, "Connection test", "skipped - host server is not running"));
        }

        return results;
    }

    private static void checkTls(List<CheckResult> results) {
        if (serverConfig.disableInternalTLS) {
            results.add(new CheckResult(Status.WARN, "TLS certificate",
                    "internal TLS is disabled - clients can only connect through a reverse proxy that terminates TLS itself"));
            return;
        }

        X509Certificate cert;
        try {
            cert = NetUtils.loadCertificate(serverCertFile);
        } catch (Exception e) {
            cert = null;
        }

        if (cert == null) {
            results.add(new CheckResult(Status.FAIL, "TLS certificate",
                    "certificate could not be loaded from " + serverCertFile + " - delete " + privateDir + " cert/key files and restart to regenerate"));
            return;
        }

        try {
            cert.checkValidity();
        } catch (CertificateExpiredException e) {
            results.add(new CheckResult(Status.FAIL, "TLS certificate",
                    "certificate EXPIRED on " + cert.getNotAfter() + " - delete the cert/key files in " + privateDir + " and restart to regenerate (clients must re-verify the new fingerprint)"));
            return;
        } catch (CertificateNotYetValidException e) {
            results.add(new CheckResult(Status.FAIL, "TLS certificate",
                    "certificate not valid until " + cert.getNotBefore() + " - is the system clock correct?"));
            return;
        }

        results.add(new CheckResult(Status.OK, "TLS certificate", "valid until " + cert.getNotAfter()));
    }

    private static void checkPorts(List<CheckResult> results, int minecraftPort) {
        if (serverConfig.bindPort == -1) {
            String mcPort = minecraftPort > 0 ? String.valueOf(minecraftPort) : "?";
            results.add(new CheckResult(Status.INFO, "Port setup",
                    "sharing the Minecraft port (" + mcPort + ") using magic packets - proxies and DDoS protection (Velocity, BungeeCord, TCPShield...) usually break this; if clients get connection errors, set bindPort to a free port and portToSend to how it is reachable from outside"));
        } else if (serverConfig.portToSend == -1) {
            results.add(new CheckResult(Status.FAIL, "Port setup",
                    "bindPort is " + serverConfig.bindPort + " but portToSend is -1, so clients will try the Minecraft port and fail - set portToSend to " + serverConfig.bindPort + " (or to the externally forwarded port)"));
        } else {
            results.add(new CheckResult(Status.OK, "Port setup",
                    "dedicated port - listening on " + serverConfig.bindPort + ", clients told to use " + serverConfig.portToSend));
        }

        if (serverConfig.addressToSend == null || serverConfig.addressToSend.isBlank()) {
            results.add(new CheckResult(Status.INFO, "Address setup",
                    "addressToSend is empty - clients will connect to the same address they join Minecraft with (usually fine)"));
        }
    }

    private static void selfDial(List<CheckResult> results, int minecraftPort) {
        String host = (serverConfig.addressToSend == null || serverConfig.addressToSend.isBlank())
                ? "127.0.0.1" : serverConfig.addressToSend.trim();
        int port = serverConfig.portToSend != -1 ? serverConfig.portToSend
                : (serverConfig.bindPort != -1 ? serverConfig.bindPort : minecraftPort);
        boolean requiresMagic = serverConfig.bindPort == -1 || serverConfig.requireMagicPackets;

        String target = host + ":" + port + (requiresMagic ? " (magic packets)" : "");
        String expectedFingerprint = hostServer.getCertificateFingerprint();

        Jsons.ModpackAddresses addresses = new Jsons.ModpackAddresses(
                new InetSocketAddress(host, port),
                new InetSocketAddress(host, minecraftPort),
                requiresMagic);

        AtomicReference<String> servedFingerprint = new AtomicReference<>();
        DownloadClient client = DownloadClient.tryCreate(addresses, Secrets.generateSecret().secretBytes(), 1, certificate -> {
            try {
                servedFingerprint.set(NetUtils.getFingerprint(certificate));
            } catch (CertificateEncodingException e) {
                return false;
            }
            return expectedFingerprint == null || expectedFingerprint.equals(servedFingerprint.get());
        });

        if (client != null) {
            client.close();
            results.add(new CheckResult(Status.OK, "Connection test",
                    "connected to " + target + ", TLS handshake OK, certificate matches"));
        } else if (servedFingerprint.get() != null && !Objects.equals(expectedFingerprint, servedFingerprint.get())) {
            results.add(new CheckResult(Status.FAIL, "Connection test",
                    "connected to " + target + " but a DIFFERENT certificate was served - something in between (reverse proxy?) is terminating TLS; clients will see a fingerprint that does not match yours"));
        } else {
            results.add(new CheckResult(Status.FAIL, "Connection test",
                    "could not connect to " + target + " - check firewall/port forwarding, and that any proxy passes this port through untouched (see log for the exact error); note: this test ran from the server machine itself, so external-only network issues may not show here"));
        }
    }
}
