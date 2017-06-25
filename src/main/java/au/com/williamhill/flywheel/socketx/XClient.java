package au.com.williamhill.flywheel.socketx;

import java.net.*;
import java.util.*;

public interface XClient<E extends XEndpoint> extends AutoCloseable {
  E connect(URI uri, XEndpointListener<? super E> listener) throws Exception;

  Collection<E> getEndpoints();
}
