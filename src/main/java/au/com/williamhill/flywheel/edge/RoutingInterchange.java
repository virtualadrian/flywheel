package au.com.williamhill.flywheel.edge;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

import org.slf4j.*;

import com.obsidiandynamics.indigo.*;

import au.com.williamhill.flywheel.frame.*;
import au.com.williamhill.flywheel.topic.*;

public final class RoutingInterchange implements Interchange {
  private static final Logger LOG = LoggerFactory.getLogger(RoutingInterchange.class);

  private final ActorSystem system;

  private final ActorRef routerRef = ActorRef.of(TopicRouter.ROLE);

  public RoutingInterchange() {
    final BiConsumer<ActorSystem, Throwable> loggingExceptionHandler = (sys, t) -> {
      LOG.warn("Exception in actor thread", t);
    };
    
    this.system = new ActorSystemConfig() {{
      executor = ActorSystemConfig.ExecutorChoice.FIXED_THREAD_POOL;
      exceptionHandler = loggingExceptionHandler.andThen(ActorSystemConfig.ExceptionHandlerChoice.DRAIN);
    }}
    .createActorSystem()
    .on(TopicRouter.ROLE).withConfig(new ActorConfig() {{
      bias = 10;
    }}).cue(() -> new TopicRouter(new TopicConfig()));
  }

  @Override
  public void onOpen(EdgeNexus nexus) {
    if (LOG.isDebugEnabled()) LOG.debug("{}: opened", nexus);
    final Subscriber subscriber = d -> {
      if (LOG.isTraceEnabled()) LOG.trace("{}: delivering {}", nexus, d.getPayload());
      nexus.sendAuto(d.getPayload(), null);
    };
    nexus.getSession().setSubscription(new RoutingSubscription(subscriber));
  }

  @Override
  public void onClose(EdgeNexus nexus) {
    if (LOG.isDebugEnabled()) LOG.debug("{}: closed", nexus);
    final RoutingSubscription subscription = nexus.getSession().getSubscription();
    if (subscription == null) {
      LOG.error("{}: no subscription", nexus);
      return;
    }
    for (Topic topic : subscription.getSubscribedTopics()) {
      if (LOG.isTraceEnabled()) LOG.trace("{}: unsubscribing from {}", nexus, topic);
      system.tell(routerRef, new Unsubscribe(topic, subscription.getSubscriber()));
    }
  }

  @Override
  public CompletableFuture<Void> onBind(EdgeNexus nexus, Set<String> subscribe, Set<String> unsubscribe) {
    if (subscribe.isEmpty() && unsubscribe.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    final RoutingSubscription subscription = nexus.getSession().getSubscription();
    if (subscription == null) {
      LOG.error("{}: no subscription", nexus);
      throw new IllegalStateException("No subscription set for " + nexus);
    }

    final CompletableFuture<Void> future = new CompletableFuture<>();
    final List<Topic> subTopics = subscribe.stream().map(t -> Topic.of(t)).collect(Collectors.toList());
    final List<Topic> unsubTopics = unsubscribe.stream().map(t -> Topic.of(t)).collect(Collectors.toList());

    system.ingress(a -> {
      final List<SubscribeResponse> subResponses = new ArrayList<>(subTopics.size());
      final List<UnsubscribeResponse> unsubResponses = new ArrayList<>(unsubTopics.size());

      for (final Topic topic : subTopics) {
        a.to(routerRef).ask(new Subscribe(topic, subscription.getSubscriber()))
        .onFault(f -> {
          LOG.warn("{}: fault while handling subscription to topic {}: {}", nexus, topic, f);
          future.completeExceptionally(new FaultException(f.getReason()));
        })
        .onResponse(r -> {
          subResponses.add(r.body());
          subscription.addTopic(topic);
          checkComplete(future, subTopics, subResponses, unsubTopics, unsubResponses);
        });
      }

      for (final Topic topic : unsubTopics) {
        a.to(routerRef).ask(new Unsubscribe(topic, subscription.getSubscriber()))
        .onFault(f -> {
          LOG.warn("{}: fault while handling unsubscription from topic {}: {}", nexus, topic, f);
          future.completeExceptionally(new FaultException(f.getReason()));
        })
        .onResponse(r -> {
          unsubResponses.add(r.body());
          subscription.removeTopic(topic);
          checkComplete(future, subTopics, subResponses, unsubTopics, unsubResponses);
        });
      }
    });
    return future;
  }

  private static void checkComplete(CompletableFuture<Void> future,
                                    List<Topic> subTopics, List<SubscribeResponse> subResponses,
                                    List<Topic> unsubTopics, List<UnsubscribeResponse> unsubResponses) {
    if (subResponses.size() == subTopics.size() &&
        unsubResponses.size() == unsubTopics.size()) {
      future.complete(null);
    }
  }

  @Override
  public void onPublish(EdgeNexus nexus, PublishTextFrame pub) {
    if (LOG.isTraceEnabled()) LOG.trace("{}: published {}", nexus, pub);
    system.tell(routerRef, new Publish(Topic.of(pub.getTopic()), new TextFrame(pub.getTopic(), pub.getPayload())));
  }

  @Override
  public void onPublish(EdgeNexus nexus, PublishBinaryFrame pub) {
    if (LOG.isTraceEnabled()) LOG.trace("{}: published {}", nexus, pub);
    system.tell(routerRef, new Publish(Topic.of(pub.getTopic()), new BinaryFrame(pub.getTopic(), pub.getPayload())));
  }

  @Override
  public void close() throws Exception {
    if (LOG.isDebugEnabled()) LOG.trace("Closing interchange");
    system.shutdown();
    final List<Fault> dlq = system.getDeadLetterQueue();
    if (! dlq.isEmpty()) {
      LOG.warn("Actor system had a non-empty dead-letter queue");
      for (Fault fault : dlq) {
        LOG.warn("> {}", fault);
      }
    }
  }
}
