package au.com.williamhill.flywheel.topic;

import java.util.*;

import com.obsidiandynamics.indigo.*;
import com.obsidiandynamics.yconf.*;

@Y(Topic.Mapper.class)
public final class Topic {
  public static final class Mapper implements TypeMapper {
    @Override public Object map(YObject y, Class<?> type) {
      return Topic.of(y.value());
    }
  }
  
  public static final String SEPARATOR = "/";
  public static final String SL_WILDCARD = "+";
  public static final String ML_WILDCARD = "#";
  
  private final String[] parts;
  
  Topic(String[] parts) {
    this.parts = parts;
  }
  
  public String[] getParts() {
    return parts;
  }
  
  public int length() {
    return parts.length;
  }
  
  public boolean isRoot() {
    return parts.length == 0;
  }
  
  public static Topic of(String topic) {
    if (topic.isEmpty()) {
      throw new IllegalArgumentException("Invalid topic '" + topic + "': empty topic");
    }
    
    if (topic.contains(SEPARATOR + SEPARATOR) || topic.startsWith(SEPARATOR) || topic.endsWith(SEPARATOR)) {
      throw new IllegalArgumentException("Invalid topic '" + topic + "': empty segment");
    }
    
    final String[] parts = topic.split(SEPARATOR);
    for (int i = 0; i < parts.length; i++) {
      final String part = parts[i];
      if (part.equals(ML_WILDCARD) && i != parts.length - 1) {
        throw new IllegalArgumentException("Invalid topic '" + topic + "': non-trailing multi-level wildcard");    
      }
      
      if (part.contains(SL_WILDCARD) && part.length() != SL_WILDCARD.length()) {
        throw new IllegalArgumentException("Invalid topic '" + topic + "': invalid segment '" + part + "'");
      }
      
      if (part.contains(ML_WILDCARD) && part.length() != ML_WILDCARD.length()) {
        throw new IllegalArgumentException("Invalid topic '" + topic + "': invalid segment '" + part + "'");
      }
      
      if (part.isEmpty()) {
        throw new IllegalArgumentException("Invalid topic '" + topic + "': empty segment");
      }
    }
    
    return new Topic(parts);
  }
  
  public static Topic root() {
    return new Topic(new String[0]);
  }
  
  ActorRef asRef() {
    return ActorRef.of(TopicRouter.ROLE, isRoot() ? null : toString());
  }
  
  static Topic fromRef(ActorRef ref) {
    return ref.key() != null ? of(ref.key()) : root();
  }
  
  public Topic parent() {
    if (parts.length == 0) throw new IllegalArgumentException("Root topic has no parent");
    final String[] newParts = new String[parts.length - 1];
    System.arraycopy(parts, 0, newParts, 0, newParts.length);
    return new Topic(newParts);
  }
  
  public Topic subtopic(int startIncl, int endExcl) {
    final String[] newParts = new String[endExcl - startIncl];
    System.arraycopy(parts, startIncl, newParts, 0, newParts.length);
    return new Topic(newParts);
  }
  
  public Topic append(String part) {
    final String[] newParts = new String[parts.length + 1];
    System.arraycopy(parts, 0, newParts, 0, parts.length);
    newParts[parts.length] = part;
    return new Topic(newParts);
  }
  
  public boolean isMultiLevelWildcard() {
    return parts.length > 0 && parts[parts.length - 1].equals(ML_WILDCARD);
  }
  
  public String tail() {
    if (parts.length == 0) throw new IllegalArgumentException("Cannot invoke on root topic");
    return parts[parts.length - 1];
  }
  
  public boolean accepts(Topic exact) {
    if (length() > exact.length()) return false;
    
    for (int i = 0; i < parts.length; i++) {
      final String thisPart = parts[i];
      if (thisPart.equals(ML_WILDCARD)) {
        return true;
      }
      
      final String exactPart = exact.parts[i];
      if (! thisPart.equals(SL_WILDCARD) && ! thisPart.equals(exactPart)) {
        return false;
      }
    }
    
    return length() == exact.length();
  }
  
  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + Arrays.hashCode(parts);
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
    Topic other = (Topic) obj;
    if (!Arrays.equals(parts, other.parts))
      return false;
    return true;
  }
  
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      sb.append(parts[i]);
      if (i != parts.length - 1) sb.append(SEPARATOR);
    }
    return sb.toString();
  }
}
