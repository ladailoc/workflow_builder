package com.fpt.workflow.ticketcategory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fpt.workflow.security.ActorContextProvider;
import com.fpt.workflow.shared.UuidGenerator;
import com.fpt.workflow.shared.time.PlatformClock;
import com.fpt.workflow.ticketcategory.domain.MappingSourceType;
import com.fpt.workflow.ticketcategory.domain.TicketCategory;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryMapping;
import com.fpt.workflow.ticketcategory.domain.TicketCategoryVersion;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryMappingRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryRepository;
import com.fpt.workflow.ticketcategory.repository.TicketCategoryVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketCategoryManagementService {
  private final TicketCategoryRepository categories; private final TicketCategoryVersionRepository versions;
  private final TicketCategoryMappingRepository mappings; private final CategoryValidationService validation;
  private final TicketCategoryBindingService bindingService;
  private final UuidGenerator uuids; private final ActorContextProvider actors; private final PlatformClock clock;
  private final ObjectMapper mapper;
  public TicketCategoryManagementService(TicketCategoryRepository categories,TicketCategoryVersionRepository versions,
      TicketCategoryMappingRepository mappings,CategoryValidationService validation,UuidGenerator uuids,
      ActorContextProvider actors,PlatformClock clock,ObjectMapper mapper,TicketCategoryBindingService bindingService){this.categories=categories;this.versions=versions;
    this.mappings=mappings;this.validation=validation;this.uuids=uuids;this.actors=actors;this.clock=clock;this.mapper=mapper;this.bindingService=bindingService;}

  @Transactional public TicketCategory create(CreateCategory command){Instant now=clock.now();return categories.save(TicketCategory.create(uuids.generate(),command.key(),command.name(),command.description(),command.categoryGroup(),command.icon(),actors.requireActor().actorId(),now));}
  @Transactional public TicketCategoryVersion draft(UUID categoryId,DraftCommand command){TicketCategory category=requireCategory(categoryId);if(category.getActiveDraftVersionId()!=null)return requireVersion(categoryId,category.getActiveDraftVersionId());Instant now=clock.now();TicketCategoryVersion version=versions.save(TicketCategoryVersion.draft(uuids.generate(),categoryId,(int)versions.countByTicketCategoryId(categoryId)+1,command.formVersionId(),command.workflowVersionId(),object(command.creationPolicy()),actors.requireActor().actorId(),now));category.pointToDraft(version.getId(),now);return version;}
  @Transactional public TicketCategoryVersion binding(UUID categoryId,UUID versionId,long expectedRevision,BindingCommand command){TicketCategoryVersion version=requireVersion(categoryId,versionId);version.updateBinding(expectedRevision,command.formVersionId(),command.workflowVersionId(),object(command.creationPolicy()));return version;}
  @Transactional public List<TicketCategoryMapping> replaceMappings(UUID categoryId,UUID versionId,long expectedRevision,List<MappingCommand> commands){TicketCategoryVersion version=requireVersion(categoryId,versionId);Set<UUID> targets=new HashSet<>();for(MappingCommand c:commands)if(!targets.add(c.targetWorkflowInputId()))throw new IllegalArgumentException("CATEGORY-MAPPING-DUPLICATE-TARGET");mappings.deleteAllByCategoryVersionId(versionId);mappings.flush();List<TicketCategoryMapping> saved=mappings.saveAll(commands.stream().map(c->TicketCategoryMapping.create(uuids.generate(),versionId,c.targetWorkflowInputId(),c.sourceType(),c.sourceFormFieldId(),c.sourceExpressionJson(),c.constantJson(),c.defaultJson(),c.onMissing(),c.transformJson(),c.ordinal())).toList());version.recordMappingMutation(expectedRevision);return saved;}
  @Transactional(readOnly=true) public List<CategoryIssue> validate(UUID categoryId,UUID versionId){return validation.validate(requireVersion(categoryId,versionId));}
  @Transactional(readOnly=true) public List<TicketCategoryMapping> mappings(UUID categoryId,UUID versionId){requireVersion(categoryId,versionId);return mappings.findAllByCategoryVersionIdOrderByOrdinalAsc(versionId);}
  @Transactional public TicketCategoryVersion publish(UUID categoryId,UUID versionId,long expectedRevision){TicketCategory category=requireCategory(categoryId);TicketCategoryVersion version=requireVersion(categoryId,versionId);if(version.getRevision()!=expectedRevision)throw new IllegalStateException("STALE_CATEGORY_REVISION");List<CategoryIssue> issues=validation.validate(version);if(issues.stream().anyMatch(i->"ERROR".equals(i.severity())))throw new IllegalStateException("CATEGORY_PUBLISH_VALIDATION_FAILED: "+issues.stream().map(CategoryIssue::code).toList());String mappingChecksum=checksum(mapper.valueToTree(mappings.findAllByCategoryVersionIdOrderByOrdinalAsc(versionId)));String checksum=checksum(mapper.createObjectNode().put("categoryId",categoryId.toString()).put("versionNo",version.getVersionNo()).put("formVersionId",version.getFormVersionId().toString()).put("workflowVersionId",version.getWorkflowVersionId().toString()).put("mappingChecksum",mappingChecksum).set("creationPolicy",version.getCreationPolicyJson()));if(category.getCurrentPublishedVersionId()!=null){TicketCategoryVersion old=requireVersion(categoryId,category.getCurrentPublishedVersionId());if(!old.getId().equals(versionId))old.supersede();}version.publish(checksum,mappingChecksum,actors.requireActor().actorId(),clock.now());category.pointToPublished(versionId,clock.now());bindingService.syncAfterCategoryPublish(categoryId,version);return version;}
  @Transactional(readOnly=true) public List<TicketCategoryVersion> versions(UUID categoryId){requireCategory(categoryId);return versions.findAllByTicketCategoryIdOrderByVersionNoDesc(categoryId);}
  private TicketCategory requireCategory(UUID id){return categories.findById(id).orElseThrow(()->new IllegalArgumentException("TicketCategory not found: "+id));}
  private TicketCategoryVersion requireVersion(UUID categoryId,UUID id){TicketCategoryVersion v=versions.findById(id).orElseThrow(()->new IllegalArgumentException("TicketCategoryVersion not found: "+id));if(!v.getTicketCategoryId().equals(categoryId))throw new IllegalArgumentException("CategoryVersion does not belong to TicketCategory");return v;}
  private JsonNode object(JsonNode n){return n==null?JsonNodeFactory.instance.objectNode():n;}
  private String checksum(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
  public record CreateCategory(String key,String name,String description,String categoryGroup,String icon){}
  public record DraftCommand(UUID formVersionId,UUID workflowVersionId,JsonNode creationPolicy){}
  public record BindingCommand(UUID formVersionId,UUID workflowVersionId,JsonNode creationPolicy){}
  public record MappingCommand(UUID targetWorkflowInputId,MappingSourceType sourceType,UUID sourceFormFieldId,JsonNode sourceExpressionJson,JsonNode constantJson,JsonNode defaultJson,String onMissing,JsonNode transformJson,int ordinal){}
}
