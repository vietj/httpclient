package io.fabric8.kubernetes.client.internal.jdkhttp;

import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.kubernetes.client.http.WebSocket.Listener;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.net.Authenticator;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TODO:
 * - executorservice
 * - Mapping to a Reader is always UTF-8
 * - determine if write timeout should be implemented
 */
public class JdkHttpClientImpl implements HttpClient {

  private static class BuilderImpl implements Builder {

    LinkedHashMap<String, Interceptor> interceptors = new LinkedHashMap<>();
    Duration connectTimeout;
    Duration readTimeout;
    Authenticator authenticator;

    @Override
    public HttpClient build() {
      java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder();
      if (connectTimeout != null) {
        builder.connectTimeout(connectTimeout);
      }
      if (authenticator != null) {
        builder.authenticator(authenticator);
      }
      return new JdkHttpClientImpl(this, builder.build());
    }

    @Override
    public Builder readTimeout(long readTimeout, TimeUnit unit) {
      this.readTimeout = Duration.ofNanos(unit.toNanos(readTimeout));
      return this;
    }

    @Override
    public Builder connectTimeout(long connectTimeout, TimeUnit unit) {
      this.connectTimeout = Duration.ofNanos(unit.toNanos(connectTimeout));
      return this;
    }

    @Override
    public Builder forStreaming() {
      // nothing to do
      return this;
    }

    @Override
    public Builder writeTimeout(long timeout, TimeUnit timeoutUnit) {
      // nothing to do
      return this;
    }

    @Override
    public Builder addOrReplaceInterceptor(String name, Interceptor interceptor) {
      if (interceptor == null) {
        interceptors.remove(name);
      } else {
        interceptors.put(name, interceptor);
      }
      return this;
    }

    @Override
    public Builder authenticatorNone() {
      this.authenticator = new Authenticator() {

      };
      return this;
    }

    public Builder copy() {
      BuilderImpl copy = new BuilderImpl();
      copy.authenticator = this.authenticator;
      copy.connectTimeout = this.connectTimeout;
      copy.readTimeout = this.readTimeout;
      copy.interceptors = new LinkedHashMap<>(this.interceptors);
      return copy;
    }

  }

  private static class JdkHttpResponseImpl<T> implements HttpResponse<T> {

    private java.net.http.HttpResponse<T> response;

    public JdkHttpResponseImpl(java.net.http.HttpResponse<T> response) {
      this.response = response;
    }

    @Override
    public List<String> headers(String key) {
      return response.headers().allValues(key);
    }

    @Override
    public int code() {
      return response.statusCode();
    }

    @Override
    public T body() {
      return response.body();
    }

    @Override
    public HttpRequest request() {
      return new JdkHttpRequestImpl(null, response.request());
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return response.previousResponse().map(JdkHttpResponseImpl::new);
    }

  }

  private BuilderImpl builder;
  private java.net.http.HttpClient httpClient;

  public JdkHttpClientImpl(BuilderImpl builderImpl, java.net.http.HttpClient httpClient) {
    this.builder = builderImpl;
    this.httpClient = httpClient;
  }

  @Override
  public void close() {
    // nothing to do, until an executor service is introduced
  }

  @Override
  public Builder newBuilder() {
    return builder.copy();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, Class<T> type) throws IOException {
    try {
      return sendAsync(request, type).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      InterruptedIOException ie = new InterruptedIOException();
      ie.initCause(e);
      throw ie;
    } catch (ExecutionException e) {
      InterruptedIOException ie = new InterruptedIOException();
      ie.initCause(e);
      throw ie;
    }
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, Class<T> type) {
    JdkHttpRequestImpl jdkRequest = (JdkHttpRequestImpl)request;
    JdkHttpRequestImpl.BuilderImpl builderImpl = jdkRequest.newBuilder();
    for (Interceptor interceptor : builder.interceptors.values()) {
      interceptor.before(builderImpl, jdkRequest);
      jdkRequest = builderImpl.build();
    }
    BodyHandler<T> bodyHandler;
    if (type == null) {
      bodyHandler = (BodyHandler<T>) BodyHandlers.discarding();
    } else if (type == InputStream.class) {
      bodyHandler = (BodyHandler<T>) BodyHandlers.ofInputStream();
    } else if (type == String.class) {
      bodyHandler = (BodyHandler<T>) BodyHandlers.ofString();
    } else {
      bodyHandler = (responseInfo) -> {
        BodySubscriber<InputStream> upstream = BodyHandlers.ofInputStream().apply(responseInfo);

        BodySubscriber<Reader> downstream = BodySubscribers.mapping(
            upstream,
            (InputStream is) -> new InputStreamReader(is, StandardCharsets.UTF_8));
        return (BodySubscriber<T>) downstream;
      };
    }

    CompletableFuture<java.net.http.HttpResponse<T>> cf = this.httpClient.sendAsync(builderImpl.build().request, bodyHandler);

    for (Interceptor interceptor : builder.interceptors.values()) {
      cf = cf.thenCompose(response -> {
        if (response != null && !HttpResponse.isSuccessful(response.statusCode())
            && interceptor.afterFailure(builderImpl, new JdkHttpResponseImpl<T>(response))) {
          return this.httpClient.sendAsync(builderImpl.build().request, bodyHandler);
        }
        return CompletableFuture.completedFuture(response);
      });
    }

    return cf.thenApply(JdkHttpResponseImpl::new);
  }

  @Override
  public void clearPool() {
    // nothing to do
  }

  @Override
  public io.fabric8.kubernetes.client.http.WebSocket.Builder newWebSocketBuilder() {
    return new JdkWebSocketImpl.BuilderImpl(this).timeout(this.builder.readTimeout);
  }

  @Override
  public io.fabric8.kubernetes.client.http.HttpRequest.Builder newHttpRequestBuilder() {
    return new JdkHttpRequestImpl.BuilderImpl().timeout(this.builder.readTimeout);
  }

  /*
   * TODO: this may not be the best way to do this - in general
   * instead we create a reponse to hold them both
   */
  private static class WebSocketResponse {
    public WebSocketResponse(WebSocket w, java.net.http.WebSocketHandshakeException wshse) {
      this.webSocket = w;
      this.wshse = wshse;
    }
    WebSocket webSocket;
    java.net.http.WebSocketHandshakeException wshse;
  }

  public CompletableFuture<WebSocket> buildAsync(JdkWebSocketImpl.BuilderImpl webSocketBuilder, Listener listener) {
    JdkWebSocketImpl.BuilderImpl copy = webSocketBuilder.copy();

    for (Interceptor interceptor : builder.interceptors.values()) {
      interceptor.before(copy, new JdkHttpRequestImpl(null, copy.asRequest()));
    }

    CompletableFuture<WebSocket> result = new CompletableFuture<WebSocket>();

    CompletableFuture<WebSocketResponse> cf = internalBuildAsync(webSocketBuilder, listener);

    for (Interceptor interceptor : builder.interceptors.values()) {
      cf = cf.thenCompose((response) -> {
        if (response.wshse != null && response.wshse.getResponse() != null
            && interceptor.afterFailure(copy, new JdkHttpResponseImpl<>(response.wshse.getResponse()))) {
          return this.internalBuildAsync(webSocketBuilder, listener);
        }
        return CompletableFuture.completedFuture(response);
      });
    }

    // map back to the expected convention with the future completed by the response exception
    cf.whenComplete((r, t) -> {
      if (t != null) {
        result.completeExceptionally(t);
      } else if (r != null) {
        if (r.wshse != null) {
          result.completeExceptionally(new io.fabric8.kubernetes.client.http.WebSocketHandshakeException(
              new JdkHttpResponseImpl<>(r.wshse.getResponse())).initCause(r.wshse));
        } else {
          result.complete(r.webSocket);
        }
      } else {
        // shouldn't happen
        result.complete(null);
      }
    });

    return result;
  }

  /**
   * Convert the invocation of a JDK build async into a holder of both the exception and the response
   */
  public CompletableFuture<WebSocketResponse> internalBuildAsync(JdkWebSocketImpl.BuilderImpl webSocketBuilder, Listener listener) {
    java.net.http.HttpRequest request = webSocketBuilder.asRequest();
    java.net.http.WebSocket.Builder builder = this.httpClient.newWebSocketBuilder();
    request.headers().map().forEach((k, v) -> {
      v.forEach(s -> builder.header(k, s));
    });

    AtomicLong queueSize = new AtomicLong();

    // use a responseholder to convey both the exception and the websocket
    CompletableFuture<WebSocketResponse> response = new CompletableFuture<JdkHttpClientImpl.WebSocketResponse>();

    builder.buildAsync(request.uri(), new JdkWebSocketImpl.ListenerAdapter(listener, queueSize)).whenComplete((w, t) -> {
      if (t instanceof java.net.http.WebSocketHandshakeException) {
        response.complete(new WebSocketResponse(new JdkWebSocketImpl(queueSize, w), (WebSocketHandshakeException)t));
      } else if (t != null) {
        response.completeExceptionally(t);
      } else {
        response.complete(new WebSocketResponse(new JdkWebSocketImpl(queueSize, w), null));
      }
    });

    return response;
  }

  public BuilderImpl getBuilder() {
    return builder;
  }

}