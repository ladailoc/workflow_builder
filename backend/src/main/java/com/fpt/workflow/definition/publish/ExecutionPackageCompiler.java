package com.fpt.workflow.definition.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fpt.workflow.definition.validation.CanonicalDefinitionJson;
import com.fpt.workflow.definition.validation.ValidationDefinition;
import org.springframework.stereotype.Component;

@Component
public class ExecutionPackageCompiler {

  private final CanonicalDefinitionJson canonicalJson;

  public ExecutionPackageCompiler(CanonicalDefinitionJson canonicalJson) {
    this.canonicalJson = canonicalJson;
  }

  public CompiledExecutionPackage compile(ValidationDefinition definition) {
    ObjectNode packageJson = canonicalJson.compile(definition);
    packageJson.put("executionPackageSchemaVersion", 1);
    packageJson.put("subworkflowVersionResolution", "RESOLVE_AT_ACTIVATION");
    packageJson = (ObjectNode) canonicalJson.canonicalize(packageJson);
    return new CompiledExecutionPackage(packageJson, canonicalJson.checksum(packageJson));
  }

  public record CompiledExecutionPackage(ObjectNode json, String checksum) {
    public CompiledExecutionPackage {
      json = json.deepCopy();
    }
  }
}
