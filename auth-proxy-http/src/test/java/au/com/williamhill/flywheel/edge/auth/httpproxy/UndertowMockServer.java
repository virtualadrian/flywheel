package au.com.williamhill.flywheel.edge.auth.httpproxy;

import java.security.*;
import java.util.concurrent.atomic.*;

import javax.net.ssl.*;

import au.com.williamhill.flywheel.edge.auth.httpproxy.util.*;
import au.com.williamhill.flywheel.util.*;
import io.undertow.*;
import io.undertow.server.*;
import io.undertow.util.*;

/**
 *  Simple Undertow-based HTTP(S) server for mocking POST responses.<p>
 *  
 *  Uses a self-signed certificate for serving HTTPS content. The keystore is generated using the following
 *  command and placed in {@code src/test/resources}:<br/>
 *  {@code keytool -genkeypair -keyalg RSA -keysize 4096 -keystore keystore.jks -keypass keypass -storepass storepass -validity 99999}
 */
final class UndertowMockServer {
  private final Undertow server;
  
  private final int httpPort;
  
  private final int httpsPort;
  
  private final AtomicInteger requests = new AtomicInteger();
  
  private final String responseJson;

  UndertowMockServer(String path, String responseJson) throws Exception {
    final KeyStore keyStore = SSLUtils
        .loadKeyStore(HttpProxyAuthBenchmark.class.getClassLoader().getResourceAsStream("keystore.jks"), "storepass");
    final SSLContext context = SSLUtils.createSSLContext(keyStore, keyStore, "keypass");
    httpPort = SocketTestSupport.getAvailablePort(8090);
    httpsPort = SocketTestSupport.getAvailablePort(8443);
    this.responseJson = responseJson;
    server = Undertow.builder()
        .addHttpListener(httpPort, "0.0.0.0")
        .addHttpsListener(httpsPort, "0.0.0.0", context)
        .setHandler(Handlers.routing().post(path, this::post))
        .build();
  }
  
  private void post(HttpServerExchange exchange) {
    requests.incrementAndGet();
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
    exchange.getResponseSender().send(responseJson);
    exchange.endExchange();
  }
  
  void start() {
    server.start();
  }
  
  void stop() {
    server.stop();
  }
  
  int getPort(boolean https) {
    return https ? httpsPort : httpPort;
  }
  
  AtomicInteger getRequests() {
    return requests;
  }
}