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

/* $Id$ */
package com.linkedin.r2.sample;


import com.linkedin.r2.filter.FilterChain;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.sample.echo.EchoServiceImpl;
import com.linkedin.r2.sample.echo.OnExceptionEchoService;
import com.linkedin.r2.sample.echo.ThrowingEchoService;
import com.linkedin.r2.sample.echo.rest.RestEchoServer;
import com.linkedin.r2.transport.common.Client;
import com.linkedin.r2.transport.common.Server;
import com.linkedin.r2.transport.common.bridge.client.TransportClient;
import com.linkedin.r2.transport.common.bridge.client.TransportClientAdapter;
import com.linkedin.r2.transport.common.bridge.server.TransportDispatcher;
import com.linkedin.r2.transport.common.bridge.server.TransportDispatcherBuilder;
import com.linkedin.r2.transport.http.client.HttpClientFactory;
import com.linkedin.r2.transport.http.common.HttpProtocolVersion;
import com.linkedin.r2.transport.http.server.HttpServerFactory;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;


/**
 * @author Chris Pettitt
 * @version $Revision$
 */
public class Bootstrap
{
  private static final int HTTP_PORT = 8877;
  private static final int HTTPS_PORT = 8443;

  private static final URI ECHO_URI = URI.create("/echo");
  private static final URI ON_EXCEPTION_ECHO_URI = URI.create("/on-exception-echo");
  private static final URI THROWING_ECHO_URI = URI.create("/throwing-echo");

  public static Server createHttpServer(FilterChain filters)
  {
    return createHttpServer(HTTP_PORT, filters);
  }

  public static Server createHttpServer(int port, FilterChain filters)
  {
    return createHttpServer(port, filters, R2Constants.DEFAULT_REST_OVER_STREAM);
  }

  public static Server createHttpServer(int port, FilterChain filters, boolean restOverStream)
  {
    return new HttpServerFactory(filters)
        .createServer(port, createDispatcher(), restOverStream);
  }

  public static Server createH2cServer(int port, FilterChain filters, boolean restOverStream)
  {
    return new HttpServerFactory(filters)
        .createH2cServer(port, createDispatcher(), restOverStream);
  }

  public static Server createHttpsServer(String keyStore, String keyStorePassword, FilterChain filters)
  {
    return createHttpsServer(HTTPS_PORT, keyStore, keyStorePassword, filters);
  }

  public static Server createHttpsServer(int sslPort, String keyStore, String keyStorePassword, FilterChain filters)
  {
    return createHttpsServer(sslPort, keyStore, keyStorePassword, filters, R2Constants.DEFAULT_REST_OVER_STREAM);
  }

  public static Server createHttpsServer(int sslPort, String keyStore, String keyStorePassword, FilterChain filters, boolean restOverStream)
  {
    return new HttpServerFactory(filters)
        .createHttpsServer(HTTP_PORT, sslPort, keyStore, keyStorePassword, createDispatcher(),
            HttpServerFactory.DEFAULT_SERVLET_TYPE, restOverStream);
  }

  public static Client createHttpClient(FilterChain filters, boolean restOverStream)
  {
    HashMap<String, String> properties = new HashMap<>();
    properties.put(HttpClientFactory.HTTP_PROTOCOL_VERSION, HttpProtocolVersion.HTTP_1_1.name());
    final TransportClient client = new HttpClientFactory.Builder()
        .setFilterChain(filters)
        .build()
        .getClient(properties);
    return new TransportClientAdapter(client, restOverStream);
  }

  public static Client createHttpsClient(FilterChain filters, boolean restOverStream, SSLContext sslContext, SSLParameters sslParameters)
  {
    HashMap<String, Object> properties = new HashMap<>();
    properties.put(HttpClientFactory.HTTP_SSL_CONTEXT, sslContext);
    properties.put(HttpClientFactory.HTTP_SSL_PARAMS, sslParameters);
    properties.put(HttpClientFactory.HTTP_PROTOCOL_VERSION, HttpProtocolVersion.HTTP_1_1.name());
    final TransportClient client = new HttpClientFactory.Builder()
        .setFilterChain(filters)
        .build()
        .getClient(properties);
    return new TransportClientAdapter(client, restOverStream);
  }

  public static Client createHttp2Client(FilterChain filters, boolean restOverStream)
  {
    HashMap<String, String> properties = new HashMap<>();
    properties.put(HttpClientFactory.HTTP_PROTOCOL_VERSION, HttpProtocolVersion.HTTP_2.name());
    final TransportClient client = new HttpClientFactory.Builder()
        .setFilterChain(filters)
        .build()
        .getClient(properties);
    return new TransportClientAdapter(client, restOverStream);
  }

  public static Client createHttpClient(FilterChain filters)
  {
    return createHttpClient(filters, R2Constants.DEFAULT_REST_OVER_STREAM);
  }

  public static URI createHttpURI(URI relativeURI)
  {
    return createHttpURI(HTTP_PORT, relativeURI);
  }

  public static URI createHttpURI(int port, URI relativeURI)
  {
    return URI.create("http://localhost:" + port + relativeURI);
  }

  public static URI createHttpsURI(URI relativeURI)
  {
    return createHttpsURI(HTTPS_PORT, relativeURI);
  }

  public static URI createHttpsURI(int port, URI relativeURI)
  {
    return URI.create("https://localhost:" + port + relativeURI);
  }

  public static URI getEchoURI()
  {
    return ECHO_URI;
  }

  public static URI getOnExceptionEchoURI()
  {
    return ON_EXCEPTION_ECHO_URI;
  }

  public static URI getThrowingEchoURI()
  {
    return THROWING_ECHO_URI;
  }

  private static TransportDispatcher createDispatcher()
  {
    return new TransportDispatcherBuilder()
            .addRestHandler(ECHO_URI, new RestEchoServer(new EchoServiceImpl()))
            .addRestHandler(ON_EXCEPTION_ECHO_URI, new RestEchoServer(new OnExceptionEchoService()))
            .addRestHandler(THROWING_ECHO_URI, new RestEchoServer(new ThrowingEchoService()))
            .build();
  }
}
