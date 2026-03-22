package pl.skidam.automodpack_core.auth.dnssec;

import org.xbill.DNS.*;
import pl.skidam.automodpack_core.config.Jsons;

import java.net.IDN;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class DnsjavaDnssecEndpointVerifier implements DnssecEndpointVerifier {
    static final String TXT_PREFIX = "endpoint=v1:";
    static final String CLOUDFLARE_DOH_URI = "https://cloudflare-dns.com/dns-query";
    private static final String CLOUDFLARE_DOH_NAME = "Cloudflare DoH";
    private static final String SYSTEM_RESOLVER_NAME = "System resolver";
    private static final Pattern ENDPOINT_ID_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(5);

    private final Resolver primaryResolverOverride;
    private final Resolver fallbackResolverOverride;

    public DnsjavaDnssecEndpointVerifier() {
        this(null, null);
    }

    DnsjavaDnssecEndpointVerifier(Resolver primaryResolverOverride) {
        this(primaryResolverOverride, null);
    }

    DnsjavaDnssecEndpointVerifier(Resolver primaryResolverOverride, Resolver fallbackResolverOverride) {
        this.primaryResolverOverride = primaryResolverOverride;
        this.fallbackResolverOverride = fallbackResolverOverride;
    }

    @Override
    public DnssecVerificationResult verify(DnssecVerificationRequest request) {
        LinkedHashMap<String, Jsons.DnssecDomainRecord> domainResults = new LinkedHashMap<>();
        if (request == null || request.endpointId() == null || request.endpointId().isBlank()) {
            return new DnssecVerificationResult(domainResults);
        }

        List<RouteHostname> routeHostnames = collectRouteHostnames(request.minecraftServerAddress(), request.rawTcpAddress());
        if (routeHostnames.isEmpty()) {
            return new DnssecVerificationResult(domainResults);
        }

        Resolver primaryResolver;
        String primaryInitFailure = null;
        try {
            primaryResolver = primaryResolver();
        } catch (Exception e) {
            primaryResolver = null;
            primaryInitFailure = safeMessage(e);
        }

        Resolver fallbackResolver = null;
        String fallbackInitFailure = null;

        for (RouteHostname routeHostname : routeHostnames) {
            Jsons.DnssecDomainRecord record;
            if (routeHostname.ipLiteral()) {
                record = skippedIpLiteralRecord(routeHostname);
            } else if (primaryResolver == null) {
                ResolverInit fallbackInit = ensureFallbackResolver(fallbackResolver, fallbackInitFailure);
                fallbackResolver = fallbackInit.resolver();
                fallbackInitFailure = fallbackInit.failureReason();
                record = resolveWithFallback(
                        routeHostname.hostname(),
                        request.endpointId(),
                        unavailableRecord(
                                routeHostname.hostname(),
                                buildTxtName(routeHostname.hostname()),
                                request.endpointId(),
                                CLOUDFLARE_DOH_NAME + " unavailable: " + primaryInitFailure
                        ),
                        fallbackResolver,
                        fallbackInitFailure
                );
            } else {
                ResolutionAttempt primaryAttempt = resolveHostname(primaryResolver, CLOUDFLARE_DOH_NAME, routeHostname.hostname(), request.endpointId());
                if (primaryAttempt.fallbackEligible()) {
                    ResolverInit fallbackInit = ensureFallbackResolver(fallbackResolver, fallbackInitFailure);
                    fallbackResolver = fallbackInit.resolver();
                    fallbackInitFailure = fallbackInit.failureReason();
                    record = resolveWithFallback(
                            routeHostname.hostname(),
                            request.endpointId(),
                            primaryAttempt.record(),
                            fallbackResolver,
                            fallbackInitFailure
                    );
                } else {
                    record = primaryAttempt.record();
                }
            }
            domainResults.put(routeHostname.hostname(), record);
        }

        return new DnssecVerificationResult(domainResults);
    }

    static String normalizeHostname(String hostname) {
        if (hostname == null) {
            throw new IllegalArgumentException("Hostname is null");
        }

        String normalized = hostname.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Hostname is blank");
        }

        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Hostname is blank");
        }

        return IDN.toASCII(normalized, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
    }

    static boolean isIpLiteral(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }

        String normalized = hostname.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        return normalized.contains(":") || IPV4_PATTERN.matcher(normalized).matches();
    }

    static String buildTxtName(String hostname) {
        return "_automodpack." + hostname + ".";
    }

    static String parseTxtEndpointValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        Set<String> endpoints = new LinkedHashSet<>();
        boolean sawAutomodpackValue = false;
        for (String value : values) {
            String prefix = matchingPrefix(value);
            if (prefix == null) {
                continue;
            }

            sawAutomodpackValue = true;
            String endpointId = value.substring(prefix.length());
            if (!ENDPOINT_ID_PATTERN.matcher(endpointId).matches()) {
                return "";
            }

            endpoints.add(endpointId);
        }

        if (!sawAutomodpackValue) {
            return null;
        }

        if (endpoints.size() != 1) {
            return "";
        }

        return endpoints.iterator().next();
    }

    private static String matchingPrefix(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith(TXT_PREFIX)) {
            return TXT_PREFIX;
        }
        return null;
    }

    private static List<RouteHostname> collectRouteHostnames(InetSocketAddress minecraftServerAddress, InetSocketAddress rawTcpAddress) {
        LinkedHashMap<String, RouteHostname> hostnames = new LinkedHashMap<>();
        addRouteHostname(hostnames, minecraftServerAddress);
        addRouteHostname(hostnames, rawTcpAddress);
        return new ArrayList<>(hostnames.values());
    }

    private static void addRouteHostname(Map<String, RouteHostname> hostnames, InetSocketAddress address) {
        if (address == null || address.getHostString() == null || address.getHostString().isBlank()) {
            return;
        }

        String host = address.getHostString();
        if (isIpLiteral(host)) {
            String normalizedIp = host.strip().toLowerCase(Locale.ROOT);
            hostnames.putIfAbsent(normalizedIp, new RouteHostname(normalizedIp, true));
            return;
        }

        try {
            String normalized = normalizeHostname(host);
            hostnames.putIfAbsent(normalized, new RouteHostname(normalized, false));
        } catch (IllegalArgumentException e) {
            hostnames.putIfAbsent(host, new RouteHostname(host, false));
        }
    }

    private Resolver primaryResolver() throws Exception {
        if (primaryResolverOverride != null) {
            return primaryResolverOverride;
        }
        return createCloudflareResolver();
    }

    private Resolver fallbackResolver() throws Exception {
        if (fallbackResolverOverride != null) {
            return fallbackResolverOverride;
        }
        return createSystemResolver();
    }

    private static Resolver createCloudflareResolver() {
        DohResolver resolver = new DohResolver(CLOUDFLARE_DOH_URI);
        resolver.setTimeout(DNS_TIMEOUT);
        resolver.setUsePost(true);
        resolver.setEDNS(0, 0, Flags.DO);
        return resolver;
    }

    private static Resolver createSystemResolver() throws Exception {
        ExtendedResolver resolver = new ExtendedResolver();
        resolver.setTimeout(DNS_TIMEOUT);
        resolver.setEDNS(0, 0, Flags.DO);
        return resolver;
    }

    private ResolverInit ensureFallbackResolver(Resolver currentResolver, String currentFailureReason) {
        if (currentResolver != null || currentFailureReason != null) {
            return new ResolverInit(currentResolver, currentFailureReason);
        }

        try {
            return new ResolverInit(fallbackResolver(), null);
        } catch (Exception e) {
            return new ResolverInit(null, safeMessage(e));
        }
    }

    private static Jsons.DnssecDomainRecord resolveWithFallback(
            String hostname,
            String expectedEndpointId,
            Jsons.DnssecDomainRecord primaryFailure,
            Resolver fallbackResolver,
            String fallbackInitFailure
    ) {
        if (fallbackResolver == null) {
            return unavailableRecord(
                    hostname,
                    buildTxtName(hostname),
                    expectedEndpointId,
                    appendReason(
                            primaryFailure.reason,
                            fallbackInitFailure == null
                                    ? SYSTEM_RESOLVER_NAME + " unavailable"
                                    : SYSTEM_RESOLVER_NAME + " unavailable: " + fallbackInitFailure
                    )
            );
        }

        ResolutionAttempt fallbackAttempt = resolveHostname(fallbackResolver, SYSTEM_RESOLVER_NAME, hostname, expectedEndpointId);
        return withResolverReason(primaryFailure.reason, SYSTEM_RESOLVER_NAME, fallbackAttempt.record());
    }

    private static ResolutionAttempt resolveHostname(Resolver resolver, String resolverName, String hostname, String expectedEndpointId) {
        String txtName = buildTxtName(hostname);
        long checkedAt = System.currentTimeMillis();

        try {
            Message response = queryTxt(resolver, txtName);
            if (response == null) {
                return new ResolutionAttempt(
                        unavailableRecord(hostname, txtName, expectedEndpointId, resolverName + " returned no response"),
                        true
                );
            }
            return new ResolutionAttempt(
                    classifyResponse(resolverName, hostname, txtName, expectedEndpointId, checkedAt, response),
                    false
            );
        } catch (Exception e) {
            return new ResolutionAttempt(
                    unavailableRecord(hostname, txtName, expectedEndpointId, resolverName + " unavailable: " + safeMessage(e)),
                    true
            );
        }
    }

    private static Message queryTxt(Resolver resolver, String txtName) throws Exception {
        Name name = Name.fromString(txtName);
        org.xbill.DNS.Record question = org.xbill.DNS.Record.newRecord(name, Type.TXT, DClass.IN);
        return resolver.send(Message.newQuery(question));
    }

    static Jsons.DnssecDomainRecord classifyResponse(
            String resolverName,
            String hostname,
            String txtName,
            String expectedEndpointId,
            long checkedAt,
            Message response
    ) {
        if (response == null) {
            return unavailableRecord(hostname, txtName, expectedEndpointId, resolverName + " returned no response");
        }

        if (!response.getHeader().getFlag(Flags.AD)) {
            return unavailableRecord(
                    hostname,
                    txtName,
                    expectedEndpointId,
                    resolverName + " did not return authenticated DNSSEC data (AD flag missing)"
            );
        }

        int rcode = response.getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            return new Jsons.DnssecDomainRecord(
                    hostname,
                    txtName,
                    expectedEndpointId,
                    Jsons.DnssecStatus.NXDOMAIN,
                    checkedAt,
                    "Authenticated DNSSEC NXDOMAIN"
            );
        }

        if (rcode != Rcode.NOERROR) {
            return unavailableRecord(
                    hostname,
                    txtName,
                    expectedEndpointId,
                    "Unexpected DNS response code: " + Rcode.string(rcode)
            );
        }

        List<String> txtValues = extractTxtValues(response, txtName);
        String parsedEndpointId = parseTxtEndpointValue(txtValues);
        if (parsedEndpointId == null) {
            return new Jsons.DnssecDomainRecord(
                    hostname,
                    txtName,
                    expectedEndpointId,
                    Jsons.DnssecStatus.NO_RECORD,
                    checkedAt,
                    "No authenticated AutoModpack TXT record at " + txtName
            );
        }

        if (parsedEndpointId.isEmpty()) {
            return new Jsons.DnssecDomainRecord(
                    hostname,
                    txtName,
                    expectedEndpointId,
                    Jsons.DnssecStatus.MALFORMED,
                    checkedAt,
                    "Malformed or conflicting AutoModpack TXT values at " + txtName
            );
        }

        Jsons.DnssecStatus status = parsedEndpointId.equals(expectedEndpointId)
                ? Jsons.DnssecStatus.SECURE_MATCH
                : Jsons.DnssecStatus.SECURE_MISMATCH;
        return new Jsons.DnssecDomainRecord(
                hostname,
                txtName,
                parsedEndpointId,
                status,
                checkedAt,
                status == Jsons.DnssecStatus.SECURE_MATCH
                        ? "Authenticated DNSSEC TXT matches advertised endpoint ID"
                        : "Authenticated DNSSEC TXT endpoint ID does not match advertised endpoint ID"
        );
    }

    private static List<String> extractTxtValues(Message response, String txtName) {
        try {
            Name expectedName = Name.fromString(txtName);
            List<String> txtValues = new ArrayList<>();
            for (org.xbill.DNS.Record record : response.getSection(Section.ANSWER)) {
                if (record instanceof TXTRecord txtRecord && expectedName.equals(record.getName())) {
                    txtValues.add(String.join("", txtRecord.getStrings()));
                }
            }
            return txtValues;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Jsons.DnssecDomainRecord skippedIpLiteralRecord(RouteHostname routeHostname) {
        return new Jsons.DnssecDomainRecord(
                routeHostname.hostname(),
                null,
                null,
                Jsons.DnssecStatus.SKIPPED_IP_LITERAL,
                System.currentTimeMillis(),
                "IP literal routes are not DNSSEC-verifiable"
        );
    }

    private static Jsons.DnssecDomainRecord unavailableRecord(String hostname, String txtName, String expectedEndpointId, String reason) {
        return new Jsons.DnssecDomainRecord(
                hostname,
                txtName,
                expectedEndpointId,
                Jsons.DnssecStatus.UNAVAILABLE,
                System.currentTimeMillis(),
                reason
        );
    }

    private static Jsons.DnssecDomainRecord withResolverReason(String prefix, String resolverName, Jsons.DnssecDomainRecord record) {
        if (record == null) {
            return null;
        }

        String resolverReason = record.reason;
        if (resolverReason != null && !resolverReason.isBlank()) {
            resolverReason = resolverName + ": " + resolverReason;
        }

        return new Jsons.DnssecDomainRecord(
                record.hostname,
                record.txtName,
                record.endpointId,
                record.status,
                record.checkedAt,
                appendReason(prefix, resolverReason)
        );
    }

    private static String appendReason(String prefix, String suffix) {
        if (prefix == null || prefix.isBlank()) {
            return suffix;
        }
        if (suffix == null || suffix.isBlank()) {
            return prefix;
        }
        return prefix + ". " + suffix;
    }

    private static String safeMessage(Exception e) {
        if (e == null) {
            return "Unknown DNS error";
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    private record RouteHostname(String hostname, boolean ipLiteral) {
    }

    private record ResolutionAttempt(Jsons.DnssecDomainRecord record, boolean fallbackEligible) {
    }

    private record ResolverInit(Resolver resolver, String failureReason) {
    }
}
