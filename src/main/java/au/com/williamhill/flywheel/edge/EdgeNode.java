package au.com.williamhill.flywheel.edge;

import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import org.slf4j.*;

import com.obsidiandynamics.socketx.*;
import com.obsidiandynamics.socketx.util.*;

import au.com.williamhill.flywheel.*;
import au.com.williamhill.flywheel.edge.auth.*;
import au.com.williamhill.flywheel.edge.auth.AuthChain.*;
import au.com.williamhill.flywheel.edge.backplane.*;
import au.com.williamhill.flywheel.edge.plugin.*;
import au.com.williamhill.flywheel.frame.*;
import au.com.williamhill.flywheel.frame.Error;
import au.com.williamhill.flywheel.remote.*;

public final class EdgeNode implements AutoCloseable, BackplaneConnector {
  private static final Logger LOG = LoggerFactory.getLogger(RemoteNode.class);
  
  private final EdgeNexus localNexus = new EdgeNexus(this, LocalPeer.instance());
  
  private final XServer<?> server;
  
  private final Wire wire;
  
  private final Interchange interchange;
  
  private final AuthChain<PubAuthChain> pubAuthChain;
  
  private final AuthChain<SubAuthChain> subAuthChain;
  
  private final Backplane backplane;
  
  private final List<EdgeNexus> nexuses = new CopyOnWriteArrayList<>();
  
  private final List<TopicListener> topicListeners = new ArrayList<>();
  
  private boolean loggingEnabled = true;
  
  private Plugin[] plugins;

  public <E extends XEndpoint> EdgeNode(XServerFactory<E> serverFactory,
                                        XServerConfig config,
                                        Wire wire,
                                        Interchange interchange,
                                        AuthChain<PubAuthChain> pubAuthChain,
                                        AuthChain<SubAuthChain> subAuthChain,
                                        Backplane backplane,
                                        Plugin[] plugins) throws Exception {
    pubAuthChain.validate();
    subAuthChain.validate();
    this.wire = wire;
    this.interchange = interchange;
    this.pubAuthChain = pubAuthChain;
    this.subAuthChain = subAuthChain;
    this.backplane = backplane;
    this.plugins = plugins;
    server = serverFactory.create(config, new XEndpointListener<E>() {
      @Override public void onConnect(E endpoint) {
        handleOpen(endpoint);
      }

      @Override public void onText(E endpoint, String message) {
        final EdgeNexus nexus = endpoint.getContext();
        try {
          final Frame frame = wire.decode(message);
          switch (frame.getType()) {
            case BIND:
              if (frame instanceof BindFrame) {
                final BindFrame bind = (BindFrame) frame;
                handleBind(nexus, bind);
              } else {
                if (loggingEnabled) LOG.warn("{}: unsupported frame {}", nexus, frame);
              }
              break;
              
            case PUBLISH:
              final PublishTextFrame pub = (PublishTextFrame) frame;
              handlePublish(nexus, pub);
              break;
              
            default:
              if (loggingEnabled) LOG.warn("{}: unsupported frame {}", nexus, frame);
              return;
          }
        } catch (Throwable e) {
          if (loggingEnabled) LOG.warn(String.format("%s: error processing frame\n%s", nexus, message), e);
          return;
        }
      }

      @Override public void onBinary(E endpoint, ByteBuffer message) {
        final EdgeNexus nexus = endpoint.getContext();
        try {
          final BinaryEncodedFrame frame = wire.decode(message);
          if (frame.getType() == FrameType.PUBLISH) {
            final PublishBinaryFrame pub = (PublishBinaryFrame) frame;
            handlePublish(nexus, pub);
          } else {
            if (loggingEnabled) LOG.warn("{}: unsupported frame {}", nexus, frame);
          }
        } catch (Throwable e) {
          if (loggingEnabled) LOG.warn(String.format("%s: error processing frame\n%s", nexus, BinaryUtils.dump(message)), e);
          return;
        }
      }

      @Override public void onDisconnect(E endpoint, int statusCode, String reason) {}

      @Override public void onClose(E endpoint) {
        final EdgeNexus nexus = endpoint.getContext();
        nexuses.remove(nexus);
        handleClose(nexus);
      }

      @Override public void onError(E endpoint, Throwable cause) {
        if (loggingEnabled) LOG.warn(String.format("Unexpected error on endpoint %s", endpoint), cause);
      }

      @Override public void onPing(E endpoint, ByteBuffer data) {}

      @Override public void onPong(E endpoint, ByteBuffer data) {}
    });
    
    initAuthChains();
    initBackplane();
    initPlugins();
  }
  
  private void initAuthChains() throws Exception {
    final AuthConnector pubConnector = new AuthConnector() {
      @Override public Collection<String> getActiveTopics(EdgeNexus nexus) {
        return Collections.emptyList();
      }
      @Override public void expireTopic(EdgeNexus nexus, String topic) {
        if (LOG.isDebugEnabled()) LOG.debug("{}: expiring {}", nexus, topic);
      }
    };
    for (Authenticator auth : pubAuthChain.getFilters().values()) {
      auth.attach(pubConnector);
    }
    
    final AuthConnector subConnector = new AuthConnector() {
      @Override public Collection<String> getActiveTopics(EdgeNexus nexus) {
        return nexus.getSession().getSubscription().getTopics();
      }
      @Override public void expireTopic(EdgeNexus nexus, String topic) {
        if (nexus.getSession().getSubscription().getTopics().contains(topic)) {
          if (LOG.isDebugEnabled()) LOG.debug("{}: expiring topic {}", nexus, topic);
          try {
            nexus.close();
          } catch (Exception e) {
            LOG.warn("Error closing nexus", e);
          }
        }
      }
    };
    for (Authenticator auth : subAuthChain.getFilters().values()) {
      auth.attach(subConnector);
    }
  }
  
  private void initBackplane() throws Exception {
    backplane.attach(this);
    addTopicListener(new TopicLambdaListener() {
      @Override public void onPublish(EdgeNexus nexus, PublishTextFrame pub) {
        if (! nexus.isLocal()) {
          backplane.onPublish(nexus, pub);
        }
      }
      
      @Override public void onPublish(EdgeNexus nexus, PublishBinaryFrame pub) {
        if (! nexus.isLocal()) {
          backplane.onPublish(nexus, pub);
        }
      }
    });
  }
  
  private void initPlugins() throws Exception {
    for (Plugin plugin : plugins) {
      plugin.onRun(this);
    }
  }
  
  public XServer<?> getServer() {
    return server;
  }
  
  private void handleBind(EdgeNexus nexus, BindFrame bind) {
    if (loggingEnabled && LOG.isDebugEnabled()) LOG.debug("{}: bind {}", nexus, bind);
    final Session session = nexus.getSession();
    if (session == null) {
      if (loggingEnabled) LOG.error("{}: no session", nexus);
      return;
    }
    
    if (bind.getAuth() != null) {
      session.setCredentials(bind.getAuth());
    }
    
    final String newSessionId;
    if (bind.getSessionId() != null) {
      if (session.getSessionId() == null) {
        newSessionId = bind.getSessionId();
        session.setSessionId(newSessionId);
      } else if (! session.getSessionId().equals(bind.getSessionId())) {
        if (loggingEnabled) LOG.warn("{}: attempted to change its session ID from {} to {}", 
                                     nexus, session.getSessionId(), bind.getSessionId());
        nexus.send(new BindResponseFrame(bind.getMessageId(), new GeneralError("Cannot reassign session ID")));
        return;
      } else {
        newSessionId = null;
      }
    } else {
      newSessionId = null;
    }
    
    final Set<String> toSubscribe = new HashSet<>();
    final Set<String> existing = session.getSubscription().getTopics();
    for (String topic : bind.getSubscribe()) {
      if (! existing.contains(topic)) {
        toSubscribe.add(topic);
      } else {
        if (loggingEnabled && LOG.isDebugEnabled()) LOG.debug("{}: ignoring duplicate subscription to {}", nexus, topic);    
      }
    }
    if (newSessionId != null) {
      toSubscribe.add(Flywheel.getRxTopicPrefix(newSessionId));
      toSubscribe.add(Flywheel.getRxTopicPrefix(newSessionId) + "/#");
    }

    final Set<String> toUnsubscribe = new HashSet<>();
    for (String topic : bind.getUnsubscribe()) {
      if (existing.contains(topic)) {
        toUnsubscribe.add(topic);
      } else {
        if (loggingEnabled && LOG.isDebugEnabled()) LOG.debug("{}: ignoring duplicate unsubscription from {}", nexus, topic);
      }
    }
    
    authenticateSubTopics(nexus, bind.getMessageId(), toSubscribe, () -> {
      final CompletableFuture<Void> f = interchange.onBind(nexus, toSubscribe, toUnsubscribe);
      f.whenComplete((void_, cause) -> {
        if (cause == null) {
          final BindResponseFrame bindRes = new BindResponseFrame(bind.getMessageId());
          nexus.send(bindRes);
          fireBindEvent(nexus, bind, bindRes);
        } else {
          if (loggingEnabled) LOG.warn("{}: error handling bind {}", nexus, bind);
          if (loggingEnabled) LOG.warn("", cause);
          fireBindEvent(nexus, bind, new BindResponseFrame(bind.getMessageId(), new GeneralError("Internal error")));
        }
      });      
    });
  }
  
  private void authenticateSubTopics(EdgeNexus nexus, UUID messageId, Set<String> topics, Runnable onSuccess) {
    final CombinedMatches combined = subAuthChain.getMatches(topics);
    combined.invokeAll(nexus, errors -> {
      if (errors.isEmpty()) {
        onSuccess.run();
      } else {
        if (loggingEnabled) LOG.warn("{}: subscriber authentication failed with errors {}, auth: {}", 
                                     nexus, errors, nexus.getSession().getCredentials());
        nexus.send(new BindResponseFrame(messageId, errors));
      }
    });
  }
  
  private void handlePublish(EdgeNexus nexus, PublishTextFrame pub) {
    authenticatePubTopic(nexus, pub.getTopic(), () -> {
      interchange.onPublish(nexus, pub);
      firePublishEvent(nexus, pub);
    });
  }
  
  private void handlePublish(EdgeNexus nexus, PublishBinaryFrame pub) {
    authenticatePubTopic(nexus, pub.getTopic(), () -> {
      interchange.onPublish(nexus, pub);
      firePublishEvent(nexus, pub);
    });
  }
  
  private void authenticatePubTopic(EdgeNexus nexus, String topic, Runnable onSuccess) {
    final CombinedMatches combined = pubAuthChain.getMatches(topic);
    combined.invokeAll(nexus, errors -> {
      if (errors.isEmpty()) {
        onSuccess.run();
      } else {
        if (loggingEnabled) LOG.warn("{}: publisher authentication failed with errors {}, auth: {}", 
                                     nexus, errors, nexus.getSession().getCredentials());
        sendErrors(nexus, errors);
      }
    });
  }

  private void sendErrors(EdgeNexus nexus, Collection<? extends Error> errors) {
    final String sessionId = nexus.getSession().getSessionId();
    final String errorTopic = Flywheel.getRxTopicPrefix(sessionId != null ? sessionId : "anon") + "/errors";
    nexus.send(new TextFrame(errorTopic, wire.encodeJson(new Errors(errors))));
  }
  
  private void handleOpen(XEndpoint endpoint) {
    final EdgeNexus nexus = new EdgeNexus(this, new XEndpointPeer(endpoint));
    nexuses.add(nexus);
    endpoint.setContext(nexus);
    interchange.onOpen(nexus);
    fireConnectEvent(nexus);
  }
  
  private void fireConnectEvent(EdgeNexus nexus) {
    for (TopicListener l : topicListeners) {
      l.onOpen(nexus);
    }
  }
  
  private void fireCloseEvent(EdgeNexus nexus) {
    for (TopicListener l : topicListeners) {
      l.onClose(nexus);
    }
  }
  
  private void fireBindEvent(EdgeNexus nexus, BindFrame bind, BindResponseFrame bindRes) {
    for (TopicListener l : topicListeners) {
      l.onBind(nexus, bind, bindRes);
    }
  }
  
  private void firePublishEvent(EdgeNexus nexus, PublishTextFrame pub) {
    for (TopicListener l : topicListeners) {
      l.onPublish(nexus, pub);
    }
  }
  
  private void firePublishEvent(EdgeNexus nexus, PublishBinaryFrame pub) {
    for (TopicListener l : topicListeners) {
      l.onPublish(nexus, pub);
    }
  }
  
  private void handleClose(EdgeNexus nexus) {
    interchange.onClose(nexus);
    fireCloseEvent(nexus);
  }
  
  public void addTopicListener(TopicListener l) {
    topicListeners.add(l);
  }
  
  public void removeTopicListener(TopicListener l) {
    topicListeners.remove(l);
  }
  
  public boolean isLoggingEnabled() {
    return loggingEnabled;
  }

  public void setLoggingEnabled(boolean loggingEnabled) {
    this.loggingEnabled = loggingEnabled;
  }

  /**
   *  Obtains the currently connected non-local nexuses.
   *  
   *  @return List of nexuses.
   */
  public List<EdgeNexus> getNexuses() {
    return Collections.unmodifiableList(new ArrayList<>(nexuses));
  }
  
  @Override
  public void publish(String topic, String payload) {
    final PublishTextFrame pub = new PublishTextFrame(topic, payload);
    interchange.onPublish(localNexus, pub);
    firePublishEvent(localNexus, pub);
  }

  @Override
  public void publish(String topic, byte[] payload) {
    final PublishBinaryFrame pub = new PublishBinaryFrame(topic, payload);
    interchange.onPublish(localNexus, pub);
    firePublishEvent(localNexus, pub);
  }
  
  Wire getWire() {
    return wire;
  }

  @Override
  public void close() throws Exception {
    for (Plugin plugin : plugins) {
      plugin.close();
    }
    backplane.close();
    server.close();
    interchange.close();
    pubAuthChain.close();
    subAuthChain.close();
  }
  
  public static EdgeNodeBuilder builder() {
    return new EdgeNodeBuilder();
  }
}
