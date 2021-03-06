package au.com.williamhill.flywheel.remote;

import java.nio.*;
import java.util.concurrent.*;

import org.slf4j.*;

import com.obsidiandynamics.socketx.*;
import com.obsidiandynamics.socketx.util.*;

import au.com.williamhill.flywheel.frame.*;

final class EndpointAdapter<E extends XEndpoint> implements XEndpointListener<E> {
  private static final Logger LOG = LoggerFactory.getLogger(EndpointAdapter.class);
  
  private final RemoteNode node;
  private final RemoteNexus nexus;
  private final RemoteNexusHandler handler;
  
  EndpointAdapter(RemoteNode node, RemoteNexus nexus, RemoteNexusHandler handler) {
    this.node = node;
    this.nexus = nexus;
    this.handler = handler;
  }
  
  @Override 
  public void onConnect(E endpoint) {
    nexus.setEndpoint(endpoint);
    handler.onOpen(nexus);
  }

  @Override 
  public void onText(E endpoint, String message) {
    try {
      final TextEncodedFrame frame = node.getWire().decode(message);
      switch (frame.getType()) {
        case BIND:
          if (frame instanceof BindResponseFrame) {
            final BindResponseFrame bindRes = (BindResponseFrame) frame;
            final CompletableFuture<BindResponseFrame> f = nexus.removeBindRequest(bindRes.getMessageId());
            if (f != null) {
              f.complete(bindRes);
            } else {
              LOG.debug("Ignoring {}", bindRes);
            }
          } else {
            LOG.error("Unsupported frame {}", frame);
          }
          break;
          
        case RECEIVE:
          final TextFrame text = (TextFrame) frame;
          handler.onText(nexus, text.getTopic(), text.getPayload());
          break;
          
        default:
          LOG.error("Unsupported frame {}", frame);
          return;
      }
    } catch (Throwable e) {
      LOG.error(String.format("Error processing frame %s", message), e);
      return;
    }
  }

  @Override 
  public void onBinary(E endpoint, ByteBuffer message) {
    try {
      final BinaryEncodedFrame frame = node.getWire().decode(message);
      if (frame.getType() == FrameType.RECEIVE) {
        final BinaryFrame bin = (BinaryFrame) frame;
        handler.onBinary(nexus, bin.getTopic(), bin.getPayload());
      } else {
        LOG.error("Unsupported frame {}", frame);
      }
    } catch (Throwable e) {
      LOG.error(String.format("Error processing frame\n%s", BinaryUtils.dump(message)), e);
      return;
    }
  }

  @Override 
  public void onDisconnect(E endpoint, int statusCode, String reason) {}

  @Override
  public void onClose(E endpoint) {
    node.removeNexus(nexus);
    handler.onClose(nexus);
  }

  @Override 
  public void onError(E endpoint, Throwable cause) {
    LOG.warn("Unexpected error", cause);
  }

  @Override
  public void onPing(E endpoint, ByteBuffer data) {}

  @Override
  public void onPong(E endpoint, ByteBuffer data) {}
}
