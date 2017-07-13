package com.obsidiandynamics.yconf;

/**
 *  Specifies how an instance of a specific class can be created from a given {@link YObject} 
 *  DOM fragment.
 */
@FunctionalInterface
public interface YMapper {
  Object map(YObject y);
}