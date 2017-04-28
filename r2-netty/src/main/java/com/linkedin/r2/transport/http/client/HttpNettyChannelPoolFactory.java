package com.linkedin.r2.transport.http.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

class HttpNettyChannelPoolFactory implements ChannelPoolFactory
{
  private final Bootstrap _bootstrap;
  private final int _maxPoolSize;
  private final long _idleTimeout;
  private final int _maxPoolWaiterSize;
  private final AsyncPoolImpl.Strategy _strategy;
  private final int _minPoolSize;
  private final ChannelGroup _allChannels;
  private final ScheduledExecutorService _scheduler;
  private final int _maxConcurrentConnectionInitializations;

  HttpNettyChannelPoolFactory(int maxPoolSize, long idleTimeout, int maxPoolWaiterSize, AsyncPoolImpl.Strategy strategy,
                                  int minPoolSize, EventLoopGroup eventLoopGroup, SSLContext sslContext, SSLParameters sslParameters, int maxHeaderSize,
                                  int maxChunkSize, int maxResponseSize, ScheduledExecutorService scheduler, int maxConcurrentConnectionInitializations, ChannelGroup allChannels)
  {

    _allChannels = allChannels;
    _scheduler = scheduler;
    _maxConcurrentConnectionInitializations = maxConcurrentConnectionInitializations;
    Bootstrap bootstrap = new Bootstrap().group(eventLoopGroup)
      .channel(NioSocketChannel.class)
      .handler(new HttpClientPipelineInitializer(sslContext, sslParameters, maxHeaderSize, maxChunkSize, maxResponseSize));

    _bootstrap = bootstrap;
    _maxPoolSize = maxPoolSize;
    _idleTimeout = idleTimeout;
    _maxPoolWaiterSize = maxPoolWaiterSize;
    _strategy = strategy;
    _minPoolSize = minPoolSize;
  }

  @Override
  public AsyncPool<Channel> getPool(SocketAddress address)
  {
    return new AsyncPoolImpl<Channel>(address.toString() + " HTTP connection pool",
      new ChannelPoolLifecycle(address,
        _bootstrap,
        _allChannels,
        false),
      _maxPoolSize,
      _idleTimeout,
      _scheduler,
      _maxPoolWaiterSize,
      _strategy,
      _minPoolSize,
      new ExponentialBackOffRateLimiter(0,
        ChannelPoolLifecycle.MAX_PERIOD_BEFORE_RETRY_CONNECTIONS,
        Math.max(10, ChannelPoolLifecycle.INITIAL_PERIOD_BEFORE_RETRY_CONNECTIONS),
        _scheduler,
        _maxConcurrentConnectionInitializations)
    );
  }

  static class HttpClientPipelineInitializer extends ChannelInitializer<NioSocketChannel>
  {
    private final SSLContext _sslContext;
    private final SSLParameters _sslParameters;

    private final ChannelPoolHandler _handler = new ChannelPoolHandler();
    private final RAPResponseHandler _responseHandler = new RAPResponseHandler();

    private final int _maxHeaderSize;
    private final int _maxChunkSize;
    private final int _maxResponseSize;

    /**
     * Creates new instance.
     * @param sslContext {@link SSLContext} to be used for TLS-enabled channel pipeline.
     * @param sslParameters {@link SSLParameters} to configure {@link SSLEngine}s created
     *          from sslContext. This is somewhat redundant to
     *          SSLContext.getDefaultSSLParameters(), but those turned out to be
     *          exceedingly difficult to configure, so we can't pass all desired
     * @param maxHeaderSize
     * @param maxChunkSize
     * @param maxResponseSize
     */
    public HttpClientPipelineInitializer(SSLContext sslContext, SSLParameters sslParameters, int maxHeaderSize, int maxChunkSize, int maxResponseSize)
    {
      _maxHeaderSize = maxHeaderSize;
      _maxChunkSize = maxChunkSize;
      _maxResponseSize = maxResponseSize;
      // Check if requested parameters are present in the supported params of the context.
      // Log warning for those not present. Throw an exception if none present.
      if (sslParameters != null)
      {
        if (sslContext == null)
        {
          throw new IllegalArgumentException("SSLParameters passed with no SSLContext");
        }

        SSLParameters supportedSSLParameters = sslContext.getSupportedSSLParameters();

        if (sslParameters.getCipherSuites() != null)
        {
          checkContained(supportedSSLParameters.getCipherSuites(),
              sslParameters.getCipherSuites(),
              "cipher suite");
        }

        if (sslParameters.getProtocols() != null)
        {
          checkContained(supportedSSLParameters.getProtocols(),
              sslParameters.getProtocols(),
              "protocol");
        }
      }
      _sslContext = sslContext;
      _sslParameters = sslParameters;
    }

    /**
     * Checks if an array is completely or partially contained in another. Logs warnings
     * for one array values not contained in the other. Throws IllegalArgumentException if
     * none are.
     *
     * @param containingArray array to contain another.
     * @param containedArray array to be contained in another.
     * @param valueName - name of the value type to be included in log warning or
     *          exception.
     */
    private void checkContained(String[] containingArray,
                                String[] containedArray,
                                String valueName)
    {
      Set<String> containingSet = new HashSet<String>(Arrays.asList(containingArray));
      Set<String> containedSet = new HashSet<String>(Arrays.asList(containedArray));

      boolean changed = containedSet.removeAll(containingSet);
      if (!changed)
      {
        throw new IllegalArgumentException("None of the requested " + valueName
            + "s: " + containedSet + " are found in SSLContext");
      }

      if (!containedSet.isEmpty())
      {
        for (String paramValue : containedSet)
        {
          HttpNettyClient.LOG.warn("{} {} requested but not found in SSLContext", valueName, paramValue);
        }
      }
    }

    @Override
    protected void initChannel(NioSocketChannel ch) throws Exception
    {
      ch.pipeline().addLast("codec", new HttpClientCodec(4096, _maxHeaderSize, _maxChunkSize));
      ch.pipeline().addLast("dechunker", new HttpObjectAggregator(_maxResponseSize));
      ch.pipeline().addLast("rapiCodec", new RAPClientCodec());
      ch.pipeline().addLast("responseHandler", _responseHandler);
      if (_sslContext != null)
      {
        ch.pipeline().addLast("sslRequestHandler", new SslRequestHandler(_sslContext, _sslParameters));
      }
      ch.pipeline().addLast("channelManager", _handler);
    }
  }
}
