package au.com.williamhill.flywheel.edge.auth.httpproxy;

import static org.junit.Assert.*;

import java.io.*;
import java.net.*;

import org.junit.*;

import com.obsidiandynamics.yconf.*;

public final class HttpProxyAuthConfigTest {
  @Test
  public void test() throws IOException, URISyntaxException {
    final HttpProxyAuth auth = new MappingContext()
        .withParser(new SnakeyamlParser())
        .fromStream(HttpProxyAuthConfigTest.class.getClassLoader().getResourceAsStream("proxy-auth-http-config.yaml"))
        .map(HttpProxyAuth.class);
    assertEquals(new URI("http://localhost:8090/auth"), auth.uri);
    assertEquals(4, auth.poolSize);
    assertEquals(30000, auth.timeoutMillis);
  }
}