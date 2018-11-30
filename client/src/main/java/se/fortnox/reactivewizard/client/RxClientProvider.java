package se.fortnox.reactivewizard.client;

import com.codahale.metrics.Gauge;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.client.ConnectionProviderFactory;
import io.reactivex.netty.client.Host;
import io.reactivex.netty.client.pool.MaxConnectionsBasedStrategy;
import io.reactivex.netty.client.pool.PoolConfig;
import io.reactivex.netty.client.pool.SingleHostPoolingProviderFactory;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.internal.UnsubscribeAwareHttpClientToConnectionBridge;
import se.fortnox.reactivewizard.metrics.HealthRecorder;
import se.fortnox.reactivewizard.metrics.Metrics;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static rx.Observable.just;

/**
 * Provides httpclients for use in proxies. This needs to be a singleton as the
 * maxConnections limit is set per client. It also needs to handle both ssl and
 * non-ssl clients as this may differ between calls.
 */
@Singleton
public class RxClientProvider {
    private final ConcurrentHashMap<InetSocketAddress, HttpClient<ByteBuf, ByteBuf>> clients    = new ConcurrentHashMap<>();
    private final HttpClientConfig                                                   config;
    private final HealthRecorder                                                     healthRecorder;

    @Inject
    public RxClientProvider(HttpClientConfig config, HealthRecorder healthRecorder) {
        this.config = config;
        this.healthRecorder = healthRecorder;
    }

    public HttpClient<ByteBuf, ByteBuf> clientFor(InetSocketAddress serverInfo) {
        return clients.computeIfAbsent(serverInfo, this::buildClient);
    }

    private HttpClient<ByteBuf, ByteBuf> configureSSL(HttpClient<ByteBuf, ByteBuf> client, String host, int port, boolean isValidateCertificates) {
        if (!isValidateCertificates) {
            return client.unsafeSecure();
        }
        try {
            return client.secure((ignored) -> createSSLEngineForEachRequest(host, port));
        } catch (Throwable e) {
            throw new RuntimeException("Unable to create secure https client.", e);
        }
    }

    private HttpClient<ByteBuf, ByteBuf> buildClient(InetSocketAddress socketAddress) {
        PoolConfig<ByteBuf, ByteBuf> poolConfig = new PoolConfig<>();
        poolConfig.limitDeterminationStrategy(new MetricPublishingMaxConnectionsBasedStrategy(config.getMaxConnections(), healthRecorder));
        ConnectionProviderFactory<ByteBuf, ByteBuf> connectionProviderFactory = createConnectionProviderFactory(poolConfig);

        ConnectionProviderFactory<ByteBuf, ByteBuf> pool = new UnsubscribeAwareConnectionProviderFactory(connectionProviderFactory, poolConfig);

        HttpClient<ByteBuf, ByteBuf> client = HttpClient.newClient(pool, just(new Host(socketAddress)))
            .readTimeOut(config.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .pipelineConfigurator(UnsubscribeAwareHttpClientToConnectionBridge::configurePipeline);

        if (config.isHttps()) {
            return configureSSL(client, config.getHost(), config.getPort(), config.isValidateCertificates());
        }
        return client;
    }

    /**
     * Allows for customization of the SSLEngine
     *
     * @return An configured SSLEngine
     */
    SSLEngine configureSSLEngine(String host, int port) {
        try {
            return SSLContext.getDefault().createSSLEngine(host, port);
        } catch (Throwable e) {
            throw new RuntimeException("Unable to configure secure client", e);
        }
    }

    protected ConnectionProviderFactory<ByteBuf, ByteBuf> createConnectionProviderFactory(PoolConfig<ByteBuf, ByteBuf> poolConfig) {
        return SingleHostPoolingProviderFactory.create(poolConfig);
    }

    /**
     * Factory method that provides a new sslEngine for each connection.
     *
     * It prevents the issue when the SSL engine thinks that handshake has been made, but in fact, the connection is new.
     *
     * @return a new SSLEngine instance.
     */
    private SSLEngine createSSLEngineForEachRequest(String host,int port) {
        SSLEngine sslEngine = configureSSLEngine(host, port);
        sslEngine.setUseClientMode(true);
        return sslEngine;
    }

    /**
     * Logs number of available connections in pool and reports unhealthy when pool is exhausted.
     */
    private static class MetricPublishingMaxConnectionsBasedStrategy extends MaxConnectionsBasedStrategy {
        private final HealthRecorder healthRecorder;

        public MetricPublishingMaxConnectionsBasedStrategy(int maxConnections, HealthRecorder healthRecorder) {
            super(maxConnections);
            this.healthRecorder = healthRecorder;
            Metrics.registry().register(
                "http_client_permits_id:" + this.hashCode(),
                (Gauge<Integer>)this::getAvailablePermits
            );
        }

        @Override
        public boolean acquireCreationPermit(long acquireStartTime, TimeUnit timeUnit) {
            return healthRecorder.logStatus(this, super.acquireCreationPermit(acquireStartTime, timeUnit));
        }
    }
}
