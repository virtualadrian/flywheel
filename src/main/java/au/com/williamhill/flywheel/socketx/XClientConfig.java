package au.com.williamhill.flywheel.socketx;

import com.obsidiandynamics.yconf.*;

@Y
public class XClientConfig {
  @YInject
  public int idleTimeoutMillis = 300_000;
  
  @YInject
  public int scanIntervalMillis = 1_000;
  
  @YInject
  public XEndpointConfig endpointConfig = new XEndpointConfig();
  
  public boolean hasIdleTimeout() {
    return idleTimeoutMillis != 0;
  }
  
  public XClientConfig withIdleTimeout(int idleTimeoutMillis) {
    this.idleTimeoutMillis = idleTimeoutMillis;
    return this;
  }
  
  public XClientConfig withScanInterval(int scanIntervalMillis) {
    this.scanIntervalMillis = scanIntervalMillis;
    return this;
  }

  public XClientConfig withEndpointConfig(XEndpointConfig endpointConfig) {
    this.endpointConfig = endpointConfig;
    return this;
  }

  @Override
  public String toString() {
    return "XClientConfig [idleTimeoutMillis=" + idleTimeoutMillis + ", scanIntervalMillis=" + scanIntervalMillis
           + ", endpointConfig=" + endpointConfig + "]";
  }
}
