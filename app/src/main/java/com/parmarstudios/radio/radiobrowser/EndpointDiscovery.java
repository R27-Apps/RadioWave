package com.parmarstudios.radio.radiobrowser;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Discovers the radio browser API endpoint that suits the application.
 *
 * @author Manish Parmar
 */
public class EndpointDiscovery {

    /**
     * DNS address to resolve API endpoints.
     */
    static final String DNS_API_ADDRESS =
            "all.api.radio-browser.info";

    /**
     * The user agent to use for discovery.
     */
    private final String userAgent;

    /**
     * The optional proxy URI.
     */
    private final String proxyUri;

    /**
     * The optional proxy user.
     */
    private final String proxyUser;

    /**
     * The optional proxy password.
     */
    private final String proxyPassword;

    /**
     * Helper for resolving DNS addresses.
     *
     * @see #DNS_API_ADDRESS
     */
    private final InetAddressHelper inetAddressHelper;

    /**
     * Producer or executor services used for discovery.
     */

    private Supplier<ExecutorService> executorServiceProducer =
            () -> Executors.newFixedThreadPool(DEFAULT_THREADS);

    /**
     * Constructs a new instance.
     *
     * @param myUserAgent the user agent String to use while discovery.
     */
    public EndpointDiscovery(@NonNull final String myUserAgent) {
        this(myUserAgent, null, null, null);
    }

    /**
     * Constructs a new instance.
     *
     * @param myUserAgent     the user agent String to use while discovery.
     * @param myProxyUri      the optional URI of a HTTP proxy to use.
     * @param myProxyUser     the optional username to
     *                        authenticate with to access the proxy.
     * @param myProxyPassword the optional password
     *                        to authenticate with to access the proxy.
     */
    public EndpointDiscovery(@NonNull final String myUserAgent,
                             final String myProxyUri,
                             final String myProxyUser,
                             final String myProxyPassword) {
        this(myUserAgent,
                myProxyUri,
                myProxyUser,
                myProxyPassword,
                new InetAddressHelper());
    }

    /**
     * Constructs a new instance.
     *
     * @param myUserAgent         the user agent String to use while discovery.
     * @param myProxyUri          the optional URI of a HTTP proxy to use.
     * @param myProxyUser         the optional username to
     *                            authenticate with to access the proxy.
     * @param myProxyPassword     the optional password
     *                            to authenticate with to access the proxy.
     * @param myInetAddressHelper the internet address resolution helper.
     */
    EndpointDiscovery(@NonNull final String myUserAgent,
                      final String myProxyUri,
                      final String myProxyUser,
                      final String myProxyPassword,
                      final InetAddressHelper myInetAddressHelper) {
        this.userAgent = myUserAgent;
        this.proxyUri = myProxyUri;
        this.proxyUser = myProxyUser;
        this.proxyPassword = myProxyPassword;
        this.inetAddressHelper = myInetAddressHelper;
    }

    /**
     * Get the URLs of all API endpoints that are returned by the DNS service.
     *
     * @return the list of possible API endpoints as per DNS request.
     * Not all returned API endpoints may be working.
     * @throws UnknownHostException if there is a problem resolving the
     *                              API DNS name.
     */
    List<String> apiUrls() throws UnknownHostException {
        InetAddress[] addresses =
                inetAddressHelper.getAllByName(DNS_API_ADDRESS);
        List<String> fqdns = new ArrayList<>();
        for (InetAddress inetAddress : addresses) {
            fqdns.add(inetAddress.getCanonicalHostName());
        }
        return fqdns.stream()
                .map(s -> String.format("https://%s/", s))
                .collect(Collectors.toList());
    }

    /**
     * The default number of threads for discovery.
     */
    static final int DEFAULT_THREADS = 10;
    /**
     * The default timeout for network connecting and reading for discovery.
     */
    static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    /**
     * A discovery for one API endpoint.
     */
    static class DiscoveryResult {
        /**
         * The endpoint address for this result.
         */
        String endpoint;
        /**
         * The connection and retrieval duration in milliseconds.
         */
        long duration;
        /**
         * The stats read from the endpoint.
         */
        Stats stats;

        public DiscoveryResult(String endpoint, long duration, Stats stats) {
            this.endpoint = endpoint;
            this.duration = duration;
            this.stats = stats;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public long getDuration() {
            return duration;
        }

        public Stats getStats() {
            return stats;
        }
    }

    /**
     * Do a discovery of the API endpoints.
     *
     * @param apiUrls the possible API urls, see {@link #apiUrls()}.
     * @return the data about the discovered endpoints.
     * Unreachable endpoints are not returned.
     */
    List<DiscoveryResult> discoverApiUrls(final List<String> apiUrls) {
        ExecutorService executorService = executorServiceProducer.get();

        try {
            List<Future<DiscoveryResult>> futureList = new ArrayList<>();
            for (final String apiUrl : apiUrls) {
                Callable<DiscoveryResult> discoveryResultCallable = () -> {
                    long start = System.currentTimeMillis();
                    Log.d(this.getClass().getSimpleName(), "Starting check for {}" + apiUrl);

                    RadioBrowser radioBrowser = new RadioBrowser(
                             (new ConnectionParams.Builder().setApiUrl(apiUrl)
                                     .setTimeout(DEFAULT_TIMEOUT_MILLIS)
                                     .setUserAgent(userAgent)
                                     .setProxyUri(proxyUri)
                                     .setProxyUser(proxyUser)
                                     .setProxyPassword(proxyPassword)
                                     .build()));
                    Stats stats = radioBrowser.getServerStats();
                    long duration = System.currentTimeMillis() - start;
                    Log.d(this.getClass().getSimpleName(), "Finished check for " + apiUrl + ", took " + duration + " ms");
                    return new DiscoveryResult(apiUrl, duration, stats);
                };
                futureList.add(executorService.submit(discoveryResultCallable));
            }

            List<DiscoveryResult> discoveryResults = new ArrayList<>();
            for (Future<DiscoveryResult> future : futureList) {
                try {
                    DiscoveryResult discoveryResult =
                            future.get(
                                    DEFAULT_TIMEOUT_MILLIS,
                                    TimeUnit.MILLISECONDS);
                    discoveryResults.add(discoveryResult);
                } catch (ExecutionException
                         | TimeoutException | InterruptedException e) {
                    Log.w("Endpoint "
                            + (apiUrls.get(futureList.indexOf(future)))
                            + " had an exception", e);
                }
            }
            return discoveryResults;
        } finally {
            executorService.shutdown();
        }
    }

    /**
     * Discovers the best performing endpoint.
     *
     * @return an optional endpoint address that can be passed to
     * the {@link RadioBrowser} constructors.
     * @throws IOException when there is an IO problem while discovery.
     */
    public Optional<String> discover() throws IOException {
        List<DiscoveryResult> discoveryResults = discoverApiUrls(apiUrls());

        return discoveryResults
                .stream()
                .sorted(Comparator.comparingLong(o -> o.duration))
                .map(DiscoveryResult::getEndpoint)
                .findFirst();
    }
}