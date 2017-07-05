package au.com.williamhill.flywheel.frame;

import java.util.*;

public final class PublishBinaryFrame implements BinaryEncodedFrame {
  private final String topic;
  
  private final byte[] payload;

  public PublishBinaryFrame(String topic, byte[] payload) {
    this.topic = topic;
    this.payload = payload;
  }

  @Override
  public FrameType getType() {
    return FrameType.PUBLISH;
  }

  public final String getTopic() {
    return topic;
  }

  public final byte[] getPayload() {
    return payload;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + Arrays.hashCode(payload);
    result = prime * result + ((topic == null) ? 0 : topic.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    PublishBinaryFrame other = (PublishBinaryFrame) obj;
    if (!Arrays.equals(payload, other.payload))
      return false;
    if (topic == null) {
      if (other.topic != null)
        return false;
    } else if (!topic.equals(other.topic))
      return false;
    return true;
  }

  @Override
  public String toString() {
    return "PublishBinary [topic=" + topic + ", payload.length=" + payload.length + "]";
  }
}
