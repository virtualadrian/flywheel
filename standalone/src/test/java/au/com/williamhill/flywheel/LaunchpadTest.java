package au.com.williamhill.flywheel;

import static junit.framework.TestCase.*;

import java.io.*;

import org.junit.*;

import au.com.williamhill.flywheel.Launchpad.*;

public final class LaunchpadTest {
  @Before
  public void before() {
    clearProps();
  }
  
  @After
  public void after() {
    clearProps();
  }
  
  private static void clearProps() {
    System.clearProperty("TestLauncher.a");
    System.clearProperty("TestLauncher.b");
  }
  
  @Test(expected=LaunchpadException.class)
  public void testPathDoesNotExit() throws LaunchpadException {
    new Launchpad(new File("foo/bar"));
  }

  @Test(expected=LaunchpadException.class)
  public void testPathNotADirectory() throws LaunchpadException {
    new Launchpad(new File("conf/test/profile.yaml"));
  }

  @Test(expected=LaunchpadException.class)
  public void testProfileMissing() throws LaunchpadException {
    new Launchpad(new File("conf"));
  }

  @Test
  public void testDefault() throws LaunchpadException {
    final Launchpad launchpad = new Launchpad(new File("conf/test"));
    launchpad.launch(new String[0]);
    assertNotNull(launchpad.getProfile().launchers);
    assertEquals(TestLauncher.class, launchpad.getProfile().launchers[0].getClass());
    assertTrue(((TestLauncher) launchpad.getProfile().launchers[0]).launched);
    assertEquals("a", System.getProperty("TestLauncher.a"));
    assertEquals("b", System.getProperty("TestLauncher.b"));
  }
}
