package com.fpt.workflow.definition.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import org.springframework.stereotype.Component;

@Component
public class ExecutionPackageCompiler {

  private final CanonicalDefinitionJson canonicalJson;
  private final PlatformSemanticDefaults semanticDefaults;

  public ExecutionPackageCompiler(
      CanonicalDefinitionJson canonicalJson, PlatformSemanticDefaults semanticDefaults) {
    this.canonicalJson = canonicalJson;
    this.semanticDefaults = semanticDefaults;
  }

  public CompiledExecutionPackage compile(ValidationDefinition definition) {
    ObjectNode packageJson = canonicalJson.compile(definition);
    packageJson.put("executionPackageSchemaVersion", 1);
    packageJson.put("subworkflowVersionResolution", "RESOLVE_AT_ACTIVATION");
    packageJson.set("platformSemanticDefaults", semanticDefaults.snapshot());
    packageJson.withArray("nodes")
        .forEach(
            nodeJson -> {
              String id = nodeJson.path("id").asText();
              definition.nodes().stream()
                  .filter(node -> node.getId().toString().equals(id))
                  .findFirst()
                  .ifPresent(
                      node ->
                          ((ObjectNode) nodeJson)
                              .set("effectiveConfig", semanticDefaults.effectiveConfig(node)));
            });
    packageJson = (ObjectNode) canonicalJson.canonicalize(packageJson);
    return new CompiledExecutionPackage(packageJson, canonicalJson.checksum(packageJson));
  }

  public record CompiledExecutionPackage(ObjectNode json, String checksum) {
    public CompiledExecutionPackage {
      json = json.deepCopy();
    }
  }
}
