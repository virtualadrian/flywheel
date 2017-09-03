package au.com.williamhill.flywheel.socketx;

import static junit.framework.TestCase.*;

import java.util.*;

import javax.net.ssl.*;

import org.junit.*;

import au.com.williamhill.flywheel.socketx.ssl.*;

public final class XEndpointConfigTest {
  @Test
  public void testHighWaterMark() {
    assertEquals(1000, new DerivedEndpointConfig().withHighWaterMark(1000).highWaterMark);
  }
  
  @Test
  public void testSSLContextProvider() {
    class TestSSLContextProvider implements SSLContextProvider {
      @Override public SSLContext getSSLContext() throws Exception {
        return null;
      }
    }
    assertEquals(TestSSLContextProvider.class, 
                 new DerivedEndpointConfig()
                 .withSSLContextProvider(new TestSSLContextProvider()).sslContextProvider.getClass());
  }

  @Test
  public void testAttributes() {
    final Map<String, Object> atts = Collections.singletonMap("foo", "bar");
    assertEquals(atts, new DerivedEndpointConfig().withAttributes(atts).attributes);
  }
}
