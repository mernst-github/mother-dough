package org.mernst.http.client;

import com.google.inject.AbstractModule;
import okhttp3.*;

import javax.inject.Singleton;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class HttpClientModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(OkHttpClient.class)
        .toProvider(
            () ->
                new OkHttpClient.Builder()
                    .eventListenerFactory(
                        call ->
                            ThreadLocalRandom.current().nextDouble() >= 0.001
                                ? EventListener.NONE
                                : new EventListener() {
                                  private Map<String, Instant> starts = new HashMap<>();
                                  private Map<String, Duration> durations = new HashMap<>();

                                  private void start(String phase) {
                                    starts.put(phase, Instant.now());
                                  }

                                  private void end(String phase) {
                                    durations.put(
                                        phase, Duration.between(starts.get(phase), Instant.now()));
                                  }

                                  @Override
                                  public void callEnd(Call call) {
                                    end("call");
                                    System.out.println("-----");
                                    durations.forEach(
                                        (k, v) -> System.out.printf("%s: %s%n", k, v.toNanos()));
                                  }

                                  @Override
                                  public void callStart(Call call) {
                                    start("call");
                                  }

                                  @Override
                                  public void connectEnd(
                                      Call call,
                                      InetSocketAddress inetSocketAddress,
                                      Proxy proxy,
                                      Protocol protocol) {
                                    end("connect");
                                  }

                                  @Override
                                  public void connectStart(
                                      Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
                                    start("connect");
                                  }

                                  @Override
                                  public void dnsEnd(
                                      Call call,
                                      String domainName,
                                      List<InetAddress> inetAddressList) {
                                    end("dns");
                                  }

                                  @Override
                                  public void dnsStart(Call call, String domainName) {
                                    start("dns");
                                  }

                                  @Override
                                  public void proxySelectEnd(
                                      Call call, HttpUrl url, List<Proxy> proxies) {
                                    end("proxySelect");
                                  }

                                  @Override
                                  public void proxySelectStart(Call call, HttpUrl url) {
                                    start("proxySelect");
                                  }

                                  @Override
                                  public void requestBodyEnd(Call call, long byteCount) {
                                    end("requestBody");
                                  }

                                  @Override
                                  public void requestBodyStart(Call call) {
                                    start("requestBody");
                                  }

                                  @Override
                                  public void requestHeadersEnd(Call call, Request request) {
                                    end("requestHeaders");
                                  }

                                  @Override
                                  public void requestHeadersStart(Call call) {
                                    start("requestHeaders");
                                  }

                                  @Override
                                  public void responseBodyEnd(Call call, long byteCount) {
                                    end("responseBody");
                                  }

                                  @Override
                                  public void responseBodyStart(Call call) {
                                    start("responseBody");
                                  }

                                  @Override
                                  public void responseHeadersEnd(Call call, Response response) {
                                    end("responseHeaders");
                                  }

                                  @Override
                                  public void responseHeadersStart(Call call) {
                                    start("responseHeaders");
                                  }

                                  @Override
                                  public void secureConnectEnd(Call call, Handshake handshake) {
                                    end("secureConnect");
                                  }

                                  @Override
                                  public void secureConnectStart(Call call) {
                                    start("secureConnect");
                                  }
                                })
                    .build())
        .in(Singleton.class);
  }
}
