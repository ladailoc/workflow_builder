package com.fpt.workflow.nodetype;

@FunctionalInterface
public interface NodeTypeProvider {
  NodeTypeManifest manifest();
}
