/*
   Copyright (c) 2015 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.r2.transport.http.client;

import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.internal.ObjectUtil;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.concurrent.ScheduledExecutorService;


/**
 * Convenient class for building {@link ChannelPoolManager} with reasonable default configs.
 *
 * @author Francesco Capponi
 */
class ChannelPoolManagerBuilder
{
  private final NioEventLoopGroup _eventLoopGroup;
  private final ScheduledExecutorService _scheduler;

  private SSLContext _sslContext = null;
  private SSLParameters _sslParameters = null;
  private int _gracefulShutdownTimeout = 30000; // default value in netty
  private long _idleTimeout = HttpClientFactory.DEFAULT_IDLE_TIMEOUT;
  private int _maxHeaderSize = HttpClientFactory.DEFAULT_MAX_HEADER_SIZE;
  private int _maxChunkSize = HttpClientFactory.DEFAULT_MAX_CHUNK_SIZE;
  private long _maxResponseSize = HttpClientFactory.DEFAULT_MAX_RESPONSE_SIZE;
  private int _maxPoolSize = HttpClientFactory.DEFAULT_POOL_SIZE;
  private int _minPoolSize = HttpClientFactory.DEFAULT_POOL_MIN_SIZE;
  private int _maxConcurrentConnectionInitializations = HttpClientFactory.DEFAULT_DEFAULT_MAX_CONCURRENT_CONNECTIONS;
  private int _poolWaiterSize = HttpClientFactory.DEFAULT_POOL_WAITER_SIZE;
  private AsyncPoolImpl.Strategy _strategy = HttpClientFactory.DEFAULT_POOL_STRATEGY;
  private boolean _tcpNoDelay = true;

  /**
   * @param eventLoopGroup The NioEventLoopGroup; it is the caller's responsibility to
   *                       shut it down
   * @param scheduler      An executor; it is the caller's responsibility to shut it down
   */
  public ChannelPoolManagerBuilder(NioEventLoopGroup eventLoopGroup, ScheduledExecutorService scheduler)
  {
    _eventLoopGroup = eventLoopGroup;
    _scheduler = scheduler;
  }

  /**
   * @param sslContext {@link SSLContext}
   */
  public ChannelPoolManagerBuilder setSSLContext(SSLContext sslContext)
  {
    _sslContext = sslContext;
    return this;
  }

  /**
   * @param sslParameters {@link SSLParameters}with overloaded construct
   */
  public ChannelPoolManagerBuilder setSSLParameters(SSLParameters sslParameters)
  {
    _sslParameters = sslParameters;
    return this;
  }

  public ChannelPoolManagerBuilder setGracefulShutdownTimeout(int gracefulShutdownTimeout)
  {
    ObjectUtil.checkPositiveOrZero(gracefulShutdownTimeout, "gracefulShutdownTimeout");
    _gracefulShutdownTimeout = gracefulShutdownTimeout;
    return this;
  }

  /**
   * @param idleTimeout Interval after which idle connections will be automatically closed
   */
  public ChannelPoolManagerBuilder setIdleTimeout(long idleTimeout)
  {
    ObjectUtil.checkPositive(idleTimeout, "idleTimeout");
    _idleTimeout = idleTimeout;
    return this;
  }

  /**
   * @param maxHeaderSize Maximum size of all HTTP headers
   */
  public ChannelPoolManagerBuilder setMaxHeaderSize(int maxHeaderSize)
  {
    ObjectUtil.checkPositive(maxHeaderSize, "maxHeaderSize");
    _maxHeaderSize = maxHeaderSize;
    return this;
  }

  /**
   * @param maxChunkSize Maximum size of a HTTP chunk
   */
  public ChannelPoolManagerBuilder setMaxChunkSize(int maxChunkSize)
  {
    ObjectUtil.checkPositive(maxChunkSize, "maxChunkSize");
    _maxChunkSize = maxChunkSize;
    return this;
  }

  /**
   * @param maxResponseSize Maximum size of a HTTP response
   */
  public ChannelPoolManagerBuilder setMaxResponseSize(long maxResponseSize)
  {
    ObjectUtil.checkPositive(maxResponseSize, "maxResponseSize");
    _maxResponseSize = maxResponseSize;
    return this;
  }

  /**
   * @param maxPoolSize maximum size for each pool for each host
   */
  public ChannelPoolManagerBuilder setMaxPoolSize(int maxPoolSize)
  {
    ObjectUtil.checkPositive(maxPoolSize, "maxPoolSize");
    _maxPoolSize = maxPoolSize;
    return this;
  }

  /**
   * @param minPoolSize minimum size for each pool for each host
   */
  public ChannelPoolManagerBuilder setMinPoolSize(int minPoolSize)
  {
    ObjectUtil.checkPositiveOrZero(minPoolSize, "minPoolSize");
    _minPoolSize = minPoolSize;
    return this;
  }

  /**
   * In case of failure, this is the maximum number or connection that can be retried to establish at the same time
   */
  public ChannelPoolManagerBuilder setMaxConcurrentConnectionInitializations(int maxConcurrentConnectionInitializations) {
    ObjectUtil.checkPositive(maxConcurrentConnectionInitializations, "maxConcurrentConnectionInitializations");
    _maxConcurrentConnectionInitializations = maxConcurrentConnectionInitializations;
    return this;
  }

  /**
   * PoolWaiterSize is the max # of concurrent waiters for getting a connection/stream from the AsyncPool
   */
  public ChannelPoolManagerBuilder setPoolWaiterSize(int poolWaiterSize)
  {
    ObjectUtil.checkPositiveOrZero(poolWaiterSize, "poolWaiterSize");
    _poolWaiterSize = poolWaiterSize;
    return this;
  }

  /**
   * @param strategy The strategy used to return pool objects.
   */
  public ChannelPoolManagerBuilder setStrategy(AsyncPoolImpl.Strategy strategy)
  {
    ObjectUtil.checkNotNull(strategy, "strategy");
    _strategy = strategy;
    return this;
  }

  public ChannelPoolManagerBuilder setTcpNoDelay(boolean tcpNoDelay)
  {
    _tcpNoDelay = tcpNoDelay;
    return this;
  }

  public ChannelPoolManager buildRest()
  {
    DefaultChannelGroup channelGroup = new DefaultChannelGroup("R2 client channels", _eventLoopGroup.next());

    return new ChannelPoolManager(
      new HttpNettyChannelPoolFactoryImpl(
        _maxPoolSize,
        _idleTimeout,
        _poolWaiterSize,
        _strategy,
        _minPoolSize,
        _eventLoopGroup,
        _sslContext,
        _sslParameters,
        _maxHeaderSize,
        _maxChunkSize,
        (int) _maxResponseSize,
        _scheduler,
        _maxConcurrentConnectionInitializations,
        channelGroup),
      "R2 Stream Http2" + ChannelPoolManager.BASE_NAME,
      channelGroup);
  }

  public ChannelPoolManager buildStream()
  {
    DefaultChannelGroup channelGroup = new DefaultChannelGroup("R2 client channels", _eventLoopGroup.next());

    return new ChannelPoolManager(
      new HttpNettyStreamChannelPoolFactoryImpl(
        _maxPoolSize,
        _idleTimeout,
        _poolWaiterSize,
        _strategy,
        _minPoolSize,
        _tcpNoDelay,
        _scheduler,
        _maxConcurrentConnectionInitializations,
        _sslContext,
        _sslParameters,
        _maxHeaderSize,
        _maxChunkSize,
        _maxResponseSize,
        _eventLoopGroup,
        channelGroup),
      "R2 Stream Http1 " + ChannelPoolManager.BASE_NAME,
      channelGroup);
  }

  public ChannelPoolManager buildHttp2Stream()
  {
    DefaultChannelGroup channelGroup = new DefaultChannelGroup("R2 client channels", _eventLoopGroup.next());

    return new ChannelPoolManager(
      new Http2NettyStreamChannelPoolFactory(
        _idleTimeout,
        _poolWaiterSize,
        _tcpNoDelay,
        _scheduler,
        _sslContext,
        _sslParameters,
        _gracefulShutdownTimeout,
        _maxHeaderSize,
        _maxChunkSize,
        _maxResponseSize,
        _eventLoopGroup,
        channelGroup),
      "R2 Stream Http2" + ChannelPoolManager.BASE_NAME,
      channelGroup);
  }
}
