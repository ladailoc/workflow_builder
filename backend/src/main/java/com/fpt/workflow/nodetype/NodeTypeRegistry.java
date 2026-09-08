package com.fpt.workflow.nodetype;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Immutable registry assembled from independently registered providers. */
@Component
public final class NodeTypeRegistry {

  private final Map<NodeType, NodeTypeManifest> manifests;

  public NodeTypeRegistry(List<NodeTypeProvider> providers) {
    EnumMap<NodeType, NodeTypeManifest> collected = new EnumMap<>(NodeType.class);
    for (NodeTypeProvider provider : List.copyOf(providers)) {
      NodeTypeManifest manifest = provider.manifest();
      if (collected.putIfAbsent(manifest.nodeType(), manifest) != null) {
        throw new IllegalStateException("Duplicate node type registration: " + manifest.nodeType());
      }
    }
    manifests = Collections.unmodifiableMap(collected);
  }

  public Optional<NodeTypeManifest> find(NodeType nodeType) {
    return Optional.ofNullable(manifests.get(nodeType));
  }

  public NodeTypeManifest require(NodeType nodeType) {
    return find(nodeType)
        .orElseThrow(() -> new IllegalArgumentException("Unknown node type: " + nodeType));
  }

  public Map<NodeType, NodeTypeManifest> manifests() {
    return manifests;
  }
}
