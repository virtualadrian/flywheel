package au.com.williamhill.flywheel.edge.auth;

import static junit.framework.TestCase.*;

import java.util.concurrent.*;

import org.awaitility.*;
import org.junit.*;
import org.mockito.*;

import com.obsidiandynamics.indigo.util.*;

import au.com.williamhill.flywheel.edge.*;
import au.com.williamhill.flywheel.edge.auth.NestedAuthenticator.*;
import au.com.williamhill.flywheel.frame.*;

public final class CachedAuthenticatorTest {
  private static class TimedAllow implements NestedAuthenticator {
    private final long allowMillis;
    
    TimedAllow(long allowMillis) {
      this.allowMillis = allowMillis;
    }

    @Override
    public void verify(EdgeNexus nexus, String topic, AuthenticationOutcome outcome) {
      if (allowMillis != -1) outcome.allow(allowMillis);
      else outcome.forbidden(topic);
    }
    
    @Override public void attach(AuthConnector connector) {}
    
    @Override public void close() {}
  }
  
  private CachedAuthenticator c;
  
  @After
  public void after() throws Exception {
    if (c != null) c.close();
  }

  @Test
  public void testAllowFinite() throws Exception {
    final NestedAuthenticator delegate = new TimedAllow(30000L);
    final NestedAuthenticator delegateProxy = Mockito.spy(delegate);
    c = new CachedAuthenticator(new CachedAuthenticatorConfig().withRunIntervalMillis(1000), delegateProxy);
    final AuthenticationOutcome outcome = Mockito.mock(AuthenticationOutcome.class);
    final EdgeNexus nexus = createNexus();
    c.attach(Mockito.mock(AuthConnector.class));
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).allow(Mockito.eq(30000L));
    Mockito.verify(delegateProxy).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    
    TestSupport.sleep(1);
    Mockito.reset(outcome, delegateProxy);
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).allow(AdditionalMatchers.lt(30000L));
    Mockito.verifyNoMoreInteractions(delegateProxy);
  }

  @Test
  public void testAllowIndefinite() throws Exception {
    final NestedAuthenticator delegate = new TimedAllow(0L);
    final NestedAuthenticator delegateProxy = Mockito.spy(delegate);
    c = new CachedAuthenticator(new CachedAuthenticatorConfig().withRunIntervalMillis(1000), delegateProxy);
    final AuthenticationOutcome outcome = Mockito.mock(AuthenticationOutcome.class);
    final EdgeNexus nexus = createNexus();
    c.attach(Mockito.mock(AuthConnector.class));
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).allow(Mockito.eq(0L));
    Mockito.verify(delegateProxy).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    
    TestSupport.sleep(1);
    Mockito.reset(outcome, delegateProxy);
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).allow(Mockito.eq(0L));
    Mockito.verifyNoMoreInteractions(delegateProxy);
  }

  @Test
  public void testDeny() throws Exception {
    final NestedAuthenticator delegate = new TimedAllow(-1);
    final NestedAuthenticator delegateProxy = Mockito.spy(delegate);
    c = new CachedAuthenticator(new CachedAuthenticatorConfig().withRunIntervalMillis(0), delegateProxy);
    final AuthenticationOutcome outcome = Mockito.mock(AuthenticationOutcome.class);
    final EdgeNexus nexus = createNexus();
    c.attach(Mockito.mock(AuthConnector.class));
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).deny(Mockito.notNull(TopicAccessError.class));
    Mockito.verify(delegateProxy).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    
    assertNotNull(c.toString());
  }
  
  @Test
  public void testCacheRefreshShortMinInterval() throws Exception {
    final NestedAuthenticator delegate = new TimedAllow(1000L);
    final NestedAuthenticator delegateProxy = Mockito.spy(delegate);
    c = new CachedAuthenticator(new CachedAuthenticatorConfig()
                                .withRunIntervalMillis(1)
                                .withMinQueryIntervalMillis(1), 
                                delegateProxy);
    final AuthenticationOutcome outcome = Mockito.mock(AuthenticationOutcome.class);
    final EdgeNexus nexus = createNexus();
    c.attach(Mockito.mock(AuthConnector.class));
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome).allow(Mockito.eq(1000L));
    Mockito.verify(delegateProxy).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    
    Awaitility.dontCatchUncaughtExceptions().await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Mockito.verify(delegateProxy, Mockito.atLeast(10)).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    });
  }
  
  @Test
  public void testCacheRefreshLongMinInterval() throws Exception {
    final NestedAuthenticator delegate = new TimedAllow(1000L);
    final NestedAuthenticator delegateProxy = Mockito.spy(delegate);
    c = new CachedAuthenticator(new CachedAuthenticatorConfig()
                                .withRunIntervalMillis(1)
                                .withMinQueryIntervalMillis(1000), 
                                delegateProxy);
    final AuthenticationOutcome outcome = Mockito.mock(AuthenticationOutcome.class);
    final EdgeNexus nexus = createNexus();
    c.attach(Mockito.mock(AuthConnector.class));
    c.verify(nexus, "topic", outcome);
    Mockito.verify(outcome, Mockito.times(1)).allow(Mockito.eq(1000L));
    Mockito.verify(delegateProxy, Mockito.times(1)).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
    
    TestSupport.sleep(100);
    
    Mockito.verify(delegateProxy, Mockito.times(1)).verify(Mockito.eq(nexus), Mockito.eq("topic"), Mockito.notNull(AuthenticationOutcome.class));
  }

  private static EdgeNexus createNexus() {
    return new EdgeNexus(null, LocalPeer.instance());
  }
}
