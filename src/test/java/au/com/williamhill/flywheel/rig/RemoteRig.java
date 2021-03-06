package au.com.williamhill.flywheel.rig;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import com.google.gson.*;
import com.obsidiandynamics.await.*;
import com.obsidiandynamics.indigo.benchmark.*;
import com.obsidiandynamics.indigo.util.*;

import au.com.williamhill.flywheel.frame.*;
import au.com.williamhill.flywheel.remote.*;
import au.com.williamhill.flywheel.rig.Announce.*;
import au.com.williamhill.flywheel.topic.*;
import au.com.williamhill.flywheel.topic.TopicSpec.*;
import au.com.williamhill.flywheel.util.Exceptions.*;

public final class RemoteRig implements TestSupport, AutoCloseable, ThrowingRunnable, RemoteNexusHandler {
  private static final String CONTROL_TOPIC = "control";
  
  private static final int MIN_SAMPLES = 10_000;
  
  public static class RemoteRigConfig {
    int syncFrames;
    URI uri;
    TopicSpec topicSpec;
    boolean initiate;
    double normalMinNanos = Double.NaN;
    long printOutliersOverMillis;
    int statsPeriod;
    LogConfig log;
    
    static URI getUri(String host, int port, String path) throws URISyntaxException, MalformedURLException {
      return new URL("http", host, port, path).toURI();
    }
  }
  
  private final RemoteNode node;
  
  private final RemoteRigConfig config;
  
  private final Gson subframeGson = new Gson();
  
  private final Summary summary = new Summary();
  
  private RemoteNexus control;
  
  private long timeDiff;
  
  private volatile long startTime;
  
  private final AtomicLong received = new AtomicLong();
  
  public RemoteRig(RemoteNode node, RemoteRigConfig config) throws Exception {
    this.node = node;
    this.config = config;
  }
  
  @Override
  public void run() throws Exception {
    final String controlSessionId = openControlNexus();
    if (config.syncFrames != 0) {
      final CalibrationResult cal = calibrate();
      timeDiff = cal.timeDiff;
      if (Double.isNaN(config.normalMinNanos)) {
        config.normalMinNanos = cal.minRoundTrip / 2;
      }
    }
    announce(controlSessionId);
    connectAll();
    begin();
  }
  
  private String openControlNexus() throws Exception {
    final String controlSessionId = generateSessionId();
    if (config.log.stages) config.log.out.format("r: opening control nexus (%s)...\n", controlSessionId);
    control = node.open(config.uri, new RemoteNexusHandlerBase() {
      @Override public void onText(RemoteNexus nexus, String topic, String payload) {
        if (config.log.verbose) config.log.out.format("r: control received %s\n", payload);
        final RigSubframe subframe = RigSubframe.unmarshal(payload, subframeGson);
        if (subframe instanceof Wait) {
          awaitLater(nexus, controlSessionId, ((Wait) subframe).getExpectedMessages());
        }
      }
    });
    control.bind(new BindFrame(UUID.randomUUID(), controlSessionId, null, 
                               new String[]{getControlRxTopic(controlSessionId)}, new String[]{}, null)).get();
    return controlSessionId;
  }
  
  private void announce(String controlSessionId) {
    control.publish(new PublishTextFrame(getControlTxTopic(controlSessionId), 
                                         new Announce(Role.CONTROL, controlSessionId).marshal(subframeGson)));
  }
  
  private void awaitLater(RemoteNexus nexus, String sessionId, long expectedMessages) {
    Threads.asyncDaemon(() -> {
      try {
        if (config.log.stages) config.log.out.format("r: awaiting receival (%,d messages)...\n", expectedMessages);
        awaitReceival(expectedMessages);
        closeNexuses();
      } catch (Exception e) {
        e.printStackTrace(config.log.out);
      }
    }, "ControlAwait");
  }
  
  private void awaitReceival(long expectedMessages) throws InterruptedException {
    final int awaitSecs = 10;
    for (int triesLeft = 5; ; triesLeft--) {
      final boolean complete = Await.bounded(awaitSecs * 1000, () -> received.get() >= expectedMessages);
      if (complete) {
        break;
      } else {
        config.log.out.format("r: received %,d/%,d (%,d sec left)\n", received.get(), expectedMessages, triesLeft * awaitSecs);
        if (triesLeft == 0) {
          config.log.out.format("r: receive timed out\n");
          break;
        }
      }
    }
    final long took = System.currentTimeMillis() - startTime;
    summary.stats.await();
    summary.compute(new Elapsed() {
      @Override public long getTotalProcessed() {
        return received.get();
      }
      @Override public long getTimeTaken() {
        return took;
      }
    });
    if (! Double.isNaN(config.normalMinNanos)) {
      summary.normaliseToMinimum(config.normalMinNanos);
    }
  }
  
  private void connectAll() throws Exception {
    final List<Interest> allInterests = config.topicSpec.getAllInterests();
    
    final List<CompletableFuture<BindResponseFrame>> futures = new ArrayList<>(allInterests.size());
    for (Interest interest : allInterests) {
      for (int i = 0; i < interest.getCount(); i++) {
        final RemoteNexus nexus = node.open(config.uri, this);
        final String sessionId = generateSessionId();
        nexus.publish(new PublishTextFrame(getControlTxTopic(sessionId), 
                                           new Announce(Role.SUBSCRIBER, control.getSessionId()).marshal(subframeGson)));
        final CompletableFuture<BindResponseFrame> f = 
            nexus.bind(new BindFrame(UUID.randomUUID(), sessionId, null,
                                     new String[]{interest.getTopic().toString()}, new String[]{}, null));
        futures.add(f);
      }
    }
    
    for (CompletableFuture<BindResponseFrame> f : futures) {
      f.get();
    }
    if (config.log.verbose) config.log.out.format("r: %,d remotes connected\n", futures.size());
  }
  
  private void begin() throws Exception {
    if (config.initiate) { 
      if (config.log.stages) config.log.out.format("r: initiating benchmark...\n");
      control.publish(new PublishTextFrame(getControlTxTopic(control.getSessionId()), 
                                           new Begin().marshal(subframeGson))).get();
    } else {
      if (config.log.stages) config.log.out.format("r: awaiting initiator...\n");
    }
  }
  
  private String generateSessionId() {
    return Long.toHexString(Crypto.machineRandom());
  }
  
  private static String getControlTxTopic(String remoteId) {
    return CONTROL_TOPIC + "/" + remoteId + "/tx";
  }
  
  private static String getControlRxTopic(String remoteId) {
    return CONTROL_TOPIC + "/" + remoteId + "/rx";
  }
  
  private static final class CalibrationResult {
    final long timeDiff;
    final long minRoundTrip;
    CalibrationResult(long timeDiff, long minRoundTrip) {
      this.timeDiff = timeDiff;
      this.minRoundTrip = minRoundTrip;
    }
  }
  
  private CalibrationResult calibrate() throws Exception {
    if (config.log.stages) config.log.out.format("r: time calibration...\n");
    final String sessionId = generateSessionId();
    final String outTopic = getControlTxTopic(sessionId);
    final int discardSyncs = (int) (config.syncFrames * .25);
    final AtomicBoolean syncComplete = new AtomicBoolean();
    final AtomicInteger syncs = new AtomicInteger();
    final AtomicLong lastRemoteTransmitTime = new AtomicLong();
    final List<Long> timeDeltas = new CopyOnWriteArrayList<>();
    final AtomicLong minRoundTrip = new AtomicLong(Long.MAX_VALUE);
    
    final RemoteNexus nexus = node.open(config.uri, new RemoteNexusHandlerBase() {
      @Override public void onText(RemoteNexus nexus, String topic, String payload) {
        if (! topic.contains(sessionId)) {
          return;
        }
        final long now = System.nanoTime();
        if (config.log.verbose) config.log.out.format("r: sync text %s %s\n", topic, payload);
        final SyncResponse syncResponse = RigSubframe.unmarshal(payload, subframeGson);
        final long timeTaken = now - lastRemoteTransmitTime.get();
        final long timeDelta = now - syncResponse.getNanoTime() - timeTaken / 2;
        if (syncs.getAndIncrement() >= discardSyncs) {
          if (config.log.verbose) config.log.out.format("r: sync round-trip: %,d, delta: %,d\n", timeTaken, timeDelta);
          if (timeTaken < minRoundTrip.get()) minRoundTrip.set(timeTaken);
          timeDeltas.add(timeDelta);
        }
        
        if (timeDeltas.size() != config.syncFrames) {
          lastRemoteTransmitTime.set(System.nanoTime());
          nexus.publish(new PublishTextFrame(outTopic, new Sync(lastRemoteTransmitTime.get()).marshal(subframeGson)));
        } else {
          try {
            nexus.close();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
      
      @Override public void onClose(RemoteNexus nexus) {
        syncComplete.set(true);
      }
    });
    nexus.bind(new BindFrame(UUID.randomUUID(), sessionId, null,
                             new String[]{getControlRxTopic(sessionId)}, new String[]{}, null)).get();
    
    lastRemoteTransmitTime.set(System.nanoTime());
    
    final int waitTime = config.syncFrames * 20; // allow for an average of up to 20 ms per round-trip
    for (;;) {
      nexus.publish(new PublishTextFrame(outTopic, new Sync(lastRemoteTransmitTime.get()).marshal(subframeGson)));
      if (Await.bounded(waitTime, () -> syncComplete.get())) {
        break;
      } else {
        config.log.out.format("r: calibration timed out; retrying...\n");
      }
    }
    
    final long timeDiff = timeDeltas.stream().collect(Collectors.averagingLong(l -> l)).longValue();
    if (config.log.stages) config.log.out.format("r: calibration complete; min round trip: %,d ns, time delta: %,d ns (%s ahead)\n", 
                                                 minRoundTrip.get(), timeDiff, timeDiff >= 0 ? "sink" : "source");
    return new CalibrationResult(timeDiff, minRoundTrip.get());
  }
  
  public boolean await() throws InterruptedException {
    Await.perpetual(() -> node.getNexuses().isEmpty());
    return true;
  }

  @Override
  public void close() throws Exception {
    closeNexuses();
    node.close();
  }
  
  private void closeNexuses() throws Exception {
    final List<RemoteNexus> nexuses = node.getNexuses();
    if (nexuses.isEmpty()) return;
    
    if (config.log.stages) config.log.out.format("r: closing remotes (%,d nexuses)...\n", nexuses.size());
    for (RemoteNexus nexus : nexuses) {
      nexus.close();
    }
    for (RemoteNexus nexus : nexuses) {
      if (! nexus.awaitClose(60_000)) {
        config.log.out.format("r: timed out while waiting for close of %s\n", nexus);
      }
    }
  }
  
  public Summary getSummary() {
    return summary;
  }

  @Override
  public void onOpen(RemoteNexus nexus) {
    if (config.log.verbose) config.log.out.format("r: opened %s\n", nexus);
  }

  @Override
  public void onClose(RemoteNexus nexus) {
    if (config.log.verbose) config.log.out.format("r: closed %s\n", nexus);
  }

  @Override
  public void onText(RemoteNexus nexus, String topic, String payload) {
    ensureStartTimeSet();
    final long now = System.nanoTime();
    final int idx = payload.indexOf(' ');
    final long serverNanos = Long.valueOf(payload.substring(0, idx));
    time(topic, now, serverNanos);
  }

  @Override
  public void onBinary(RemoteNexus nexus, String topic, byte[] payload) {
    ensureStartTimeSet();
    final ByteBuffer buf = ByteBuffer.wrap(payload);
    final long now = System.nanoTime();
    final long serverNanos = buf.getLong();
    time(topic, now, serverNanos);
  }
  
  private final AtomicBoolean loggedCommencement = new AtomicBoolean();
  
  private void ensureStartTimeSet() {
    if (startTime == 0) {
      startTime = System.currentTimeMillis();
      if (config.log.stages && loggedCommencement.compareAndSet(false, true)) {
        config.log.out.format("r: benchmark commenced on %s\n", new Date(startTime));
      }
    }
  }
  
  private void time(String topic, long now, long serverNanos) {
    final long count = received.incrementAndGet();
    if (serverNanos == 0) return;
    
    final long clientNanos = serverNanos + timeDiff;
    final long took = now - clientNanos;
    final long printOutliersOverMillis = config.printOutliersOverMillis;
    if (printOutliersOverMillis != 0) {
      final long tookMillis = took / 1_000_000L;
      if (tookMillis > printOutliersOverMillis) {
        ForkJoinPool.commonPool().execute(() -> {
          config.log.out.format("r: outlier took %,d ns for topic %s on %s\n", took, topic, new Date());
        });
      }
    }
    if (config.log.verbose) config.log.out.format("r: received; latency %,d\n", took);
    final boolean sample = count <= MIN_SAMPLES || count % config.statsPeriod == 0;
    if (sample) summary.stats.executor.execute(() -> summary.stats.samples.addValue(took));
  }
}
