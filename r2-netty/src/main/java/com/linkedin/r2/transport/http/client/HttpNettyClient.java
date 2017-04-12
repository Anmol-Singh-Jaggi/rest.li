/*
   Copyright (c) 2012 LinkedIn Corp.

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

/**
 * $Id: $
 */

package com.linkedin.r2.transport.http.client;

import com.google.common.collect.Sets;
import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestRequestBuilder;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.message.stream.StreamRequest;
import com.linkedin.r2.message.stream.StreamResponse;
import com.linkedin.r2.transport.common.MessageType;
import com.linkedin.r2.transport.common.WireAttributeHelper;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.transport.common.bridge.common.TransportCallback;
import com.linkedin.r2.transport.common.bridge.common.TransportResponseImpl;
import com.linkedin.r2.transport.http.common.HttpBridge;
import com.linkedin.r2.transport.http.common.HttpProtocolVersion;
import com.linkedin.r2.util.Cancellable;
import com.linkedin.r2.util.TimeoutRunnable;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Steven Ihde
 * @author Ang Xu
 * @version $Revision: $
 */

/* package private */ class HttpNettyClient implements TransportClient
{
  static final Logger LOG = LoggerFactory.getLogger(HttpNettyClient.class);
  private static final int HTTP_DEFAULT_PORT = 80;
  private static final int HTTPS_DEFAULT_PORT = 443;

  private final ChannelPoolManager _channelPoolManager;

  private final AtomicReference<State> _state = new AtomicReference<State>(State.RUNNING);

  private enum State { RUNNING, SHUTTING_DOWN, REQUESTS_STOPPING, SHUTDOWN }

  private final ScheduledExecutorService _scheduler;
  private final ExecutorService _callbackExecutors;

  private final long _requestTimeout;
  private final long _shutdownTimeout;

  private final String _requestTimeoutMessage;
  private final AbstractJmxManager _jmxManager;
  Set<TimeoutTransportCallbackConnectionAware<RestResponse, Channel>> callbacks = Sets.newConcurrentHashSet();

  /**
   * Creates a new HttpNettyClient
   *
   * @param eventLoopGroup            The NioEventLoopGroup; it is the caller's responsibility to
   *                                  shut it down
   * @param executor                  An executor; it is the caller's responsibility to shut it down
   * @param poolSize                  Maximum size of the underlying HTTP connection pool
   * @param requestTimeout            Timeout, in ms, to get a connection from the pool or create one
   * @param idleTimeout               Interval after which idle connections will be automatically closed
   * @param shutdownTimeout           Timeout, in ms, the client should wait after shutdown is
   *                                  initiated before terminating outstanding requests
   * @param maxResponseSize           Maximum size of a HTTP response
   * @param sslContext                {@link SSLContext}
   * @param sslParameters             {@link SSLParameters}with overloaded construct
   * @param callbackExecutors         An optional EventExecutorGroup to invoke user callback
   * @param poolWaiterSize            Maximum waiters waiting on the HTTP connection pool
   * @param name                      Name of the {@link HttpNettyClient}
   * @param jmxManager                A management class that is aware of the creation/shutdown event
   *                                  of the underlying {@link ChannelPoolManager}
   * @param strategy                  The strategy used to return pool objects.
   * @param minPoolSize               Minimum number of objects in the pool. Set to zero for no minimum.
   * @param maxHeaderSize             Maximum size of all HTTP headers
   * @param maxChunkSize              Maximum size of a HTTP chunk
   * @param maxConcurrentConnections  Maximum number of concurrent connection attempts the HTTP
   *                                  connection pool can make.
   */
  public HttpNettyClient(NioEventLoopGroup eventLoopGroup,
                         ScheduledExecutorService executor,
                         int poolSize,
                         long requestTimeout,
                         long idleTimeout,
                         long shutdownTimeout,
                         int maxResponseSize,
                         SSLContext sslContext,
                         SSLParameters sslParameters,
                         ExecutorService callbackExecutors,
                         int poolWaiterSize,
                         String name,
                         AbstractJmxManager jmxManager,
                         AsyncPoolImpl.Strategy strategy,
                         int minPoolSize,
                         int maxHeaderSize,
                         int maxChunkSize,
                         int maxConcurrentConnections)
  {
    _scheduler = executor;
    _callbackExecutors = callbackExecutors == null ? eventLoopGroup : callbackExecutors;
    _requestTimeout = requestTimeout;
    _shutdownTimeout = shutdownTimeout;
    _requestTimeoutMessage = "Exceeded request timeout of " + _requestTimeout + "ms";
    _jmxManager = jmxManager;

    _channelPoolManager = new ChannelPoolManager(
      new HttpNettyChannelPoolFactoryImpl(poolSize,
        idleTimeout,
        poolWaiterSize,
        strategy,
        minPoolSize,
        eventLoopGroup,
        sslContext,
        sslParameters,
        maxHeaderSize,
        maxChunkSize, maxResponseSize, _scheduler,
        maxConcurrentConnections),
      name + ChannelPoolManager.BASE_NAME);

    _jmxManager.onProviderCreate(_channelPoolManager);
  }

  public HttpNettyClient(NioEventLoopGroup eventLoopGroup,
                         ScheduledExecutorService executor,
                         ChannelPoolManager channelPoolManager,
                         long requestTimeout,
                         long shutdownTimeout,
                         ExecutorService callbackExecutors,
                         AbstractJmxManager jmxManager)
  {
    _scheduler = executor;
    _callbackExecutors = callbackExecutors == null ? eventLoopGroup : callbackExecutors;
    _requestTimeout = requestTimeout;
    _shutdownTimeout = shutdownTimeout;
    _requestTimeoutMessage = "Exceeded request timeout of " + _requestTimeout + "ms";
    _jmxManager = jmxManager;
    _channelPoolManager = channelPoolManager;
    _jmxManager.onProviderCreate(_channelPoolManager);

  }

  /* Constructor for test purpose ONLY. */
  HttpNettyClient(ChannelPoolFactory factory,
                  ScheduledExecutorService executor,
                  int requestTimeout,
                  int shutdownTimeout,
                  int maxResponseSize)
  {
//    _maxResponseSize = maxResponseSize;
    _channelPoolManager = new ChannelPoolManager(factory);
    _scheduler = executor;
    _callbackExecutors = new DefaultEventExecutorGroup(1);
    _requestTimeout = requestTimeout;
    _shutdownTimeout = shutdownTimeout;
    _requestTimeoutMessage = "Exceeded request timeout of " + _requestTimeout + "ms";
    _jmxManager = AbstractJmxManager.NULL_JMX_MANAGER;
    _jmxManager.onProviderCreate(_channelPoolManager);
//    _maxHeaderSize = 8192;
//    _maxChunkSize = 8192;
//    _maxConcurrentConnections = Integer.MAX_VALUE;
  }

  @Override
  public void restRequest(RestRequest request,
                          RequestContext requestContext,
                          Map<String, String> wireAttrs,
                          TransportCallback<RestResponse> callback)
  {
    MessageType.setMessageType(MessageType.Type.REST, wireAttrs);
    writeRequestWithTimeout(request, requestContext, wireAttrs, HttpBridge.restToHttpCallback(callback, request));
  }

  @Override
  public void streamRequest(StreamRequest request,
                            RequestContext requestContext,
                            Map<String, String> wireAttrs,
                            TransportCallback<StreamResponse> callback)
  {
    // this method will not be exercised as long as the TransportClient is created via HttpClientFactory
    throw new UnsupportedOperationException("stream is not supported.");
  }

  @Override
  public void shutdown(final Callback<None> callback)
  {
    LOG.info("Shutdown requested");
    if (_state.compareAndSet(State.RUNNING, State.SHUTTING_DOWN))
    {
      LOG.info("Shutting down");
      final long deadline = System.currentTimeMillis() + _shutdownTimeout;
      TimeoutCallback<None> closeChannels =
        new TimeoutCallback<None>(_scheduler,
          _shutdownTimeout,
          TimeUnit.MILLISECONDS,
          new Callback<None>()
          {
            private void finishShutdown()
            {
              _state.set(State.REQUESTS_STOPPING);
              // Timeout any waiters which haven't received a Channel yet
              callbacks.forEach(callback -> errorResponse(callback, new TimeoutException("Operation did not complete before shutdown")));
              callbacks.forEach(TimeoutTransportCallbackConnectionAware::close);


              // Close all active and idle Channels
              new TimeoutRunnable(
                _scheduler, deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS, () ->
              {
                _state.set(State.SHUTDOWN);
                LOG.info("Shutdown complete");
                callback.onSuccess(None.none());
              }, "Timed out waiting for channels to close, continuing shutdown").run();
            }

            @Override
            public void onSuccess(None none)
            {
              LOG.info("All connection pools shut down, closing all channels");
              finishShutdown();
            }

            @Override
            public void onError(Throwable e)
            {
              LOG.warn("Error shutting down HTTP connection pools, ignoring and continuing shutdown", e);
              finishShutdown();
            }
          }, "Connection pool shutdown timeout exceeded (" + _shutdownTimeout + "ms)");
      _channelPoolManager.shutdown(closeChannels);
      _jmxManager.onProviderShutdown(_channelPoolManager);
    }
    else
    {
      callback.onError(new IllegalStateException("Shutdown has already been requested."));
    }
  }

  private void writeRequestWithTimeout(RestRequest request, RequestContext requestContext, Map<String, String> wireAttrs,
                                       TransportCallback<RestResponse> callback)
  {
    ExecutionCallback<RestResponse> executionCallback = new ExecutionCallback<RestResponse>(_callbackExecutors, callback);
    // By wrapping the callback in a Timeout callback before passing it along, we deny the rest
    // of the code access to the unwrapped callback.  This ensures two things:
    // 1. The user callback will always be invoked, since the Timeout will eventually expire
    // 2. The user callback is never invoked more than once
    TimeoutTransportCallback<RestResponse> timeoutCallback =
        new TimeoutTransportCallbackImpl<>(_scheduler,
            _requestTimeout,
            TimeUnit.MILLISECONDS,
            executionCallback,
            _requestTimeoutMessage);
    writeRequest(request, requestContext, wireAttrs, timeoutCallback);
  }

  private void writeRequest(RestRequest request, RequestContext requestContext, Map<String, String> wireAttrs,
                            final TimeoutTransportCallback<RestResponse> callback)
  {
    State state = _state.get();
    if (state != State.RUNNING)
    {
      errorResponse(callback, new IllegalStateException("Client is " + state));
      return;
    }
    URI uri = request.getURI();
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
    {
      errorResponse(callback, new IllegalArgumentException("Unknown scheme: " + scheme
          + " (only http/https is supported)"));
      return;
    }
    String host = uri.getHost();
    int port = uri.getPort();
    if (port == -1) {
      port = "http".equalsIgnoreCase(scheme) ? HTTP_DEFAULT_PORT : HTTPS_DEFAULT_PORT;
    }

    final RestRequest newRequest = new RestRequestBuilder(request)
        .overwriteHeaders(WireAttributeHelper.toWireAttributes(wireAttrs))
        .build();

    final SocketAddress address;
    try
    {
      // TODO investigate DNS resolution and timing
      InetAddress inetAddress = InetAddress.getByName(host);
      address = new InetSocketAddress(inetAddress, port);
      requestContext.putLocalAttr(R2Constants.REMOTE_SERVER_ADDR, inetAddress.getHostAddress());
    }
    catch (UnknownHostException e)
    {
      errorResponse(callback, e);
      return;
    }

    requestContext.putLocalAttr(R2Constants.HTTP_PROTOCOL_VERSION, HttpProtocolVersion.HTTP_1_1);

    final AsyncPool<Channel> pool;
    try
    {
      pool = _channelPoolManager.getPoolForAddress(address);
    }
    catch (IllegalStateException e)
    {
      errorResponse(callback, e);
      return;
    }

    TimeoutTransportCallbackConnectionAware<RestResponse, Channel> newCallback = new TimeoutTransportCallbackConnectionAware<RestResponse, Channel>(callback, callbacks, channel ->
    {
      if (channel != null)
      {
        channel.close();
      }
    });

    final Cancellable pendingGet = pool.get(new Callback<Channel>()
    {
      @Override
      public void onSuccess(final Channel channel)
      {
        // This handler ensures the channel is returned to the pool at the end of the
        // Netty pipeline.
        channel.attr(ChannelPoolHandler.CHANNEL_POOL_ATTR_KEY).set(pool);
        newCallback.addTimeoutTask(new Runnable()
        {
          @Override
          public void run()
          {
            AsyncPool<Channel> pool = channel.attr(ChannelPoolHandler.CHANNEL_POOL_ATTR_KEY).getAndRemove();
            if (pool != null)
            {
              pool.dispose(channel);
            }
          }
        });

        // This handler invokes the callback with the response once it arrives.
        channel.attr(RAPResponseHandler.CALLBACK_ATTR_KEY).set(newCallback);

        final State state = _state.get();
        if (state == State.REQUESTS_STOPPING || state == State.SHUTDOWN)
        {
          // In this case, we acquired a channel from the pool as request processing is halting.
          // The shutdown task might not timeout this callback, since it may already have scanned
          // all the channels for pending requests before we set the callback as the channel
          // attachment.  The TimeoutTransportCallback ensures the user callback in never
          // invoked more than once, so it is safe to invoke it unconditionally.
          errorResponse(newCallback,
            new TimeoutException("Operation did not complete before shutdown"));
          return;
        }

        // here we want the exception in outbound operations to be passed back through pipeline so that
        // the user callback would be invoked with the exception and the channel can be put back into the pool
        channel.writeAndFlush(newRequest).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
      }

      @Override
      public void onError(Throwable e)
      {
        errorResponse(callback, e);
      }
    });
    if (pendingGet != null)
    {
      newCallback.addTimeoutTask(new Runnable()
      {
        @Override
        public void run()
        {
          pendingGet.cancel();
        }
      });
    }
  }

  static <T> void errorResponse(TransportCallback<T> callback, Throwable e)
  {
    callback.onResponse(TransportResponseImpl.<T>error(e));
  }

  static Exception toException(Throwable t)
  {
    if (t instanceof Exception)
    {
      return (Exception)t;
    }
    // This could probably be improved...
    return new Exception("Wrapped Throwable", t);
  }

  /**
   * Get statistics from each channel pool. The map keys represent pool names.
   * The values are the corresponding {@link AsyncPoolStats} objects.
   *
   * @return A map of pool names and statistics.
   */
  public Map<String, PoolStats> getPoolStats()
  {
    return _channelPoolManager.getPoolStats();
  }

  // Test support

  public long getRequestTimeout()
  {
    return _requestTimeout;
  }

  public long getShutdownTimeout()
  {
    return _shutdownTimeout;
  }
}
