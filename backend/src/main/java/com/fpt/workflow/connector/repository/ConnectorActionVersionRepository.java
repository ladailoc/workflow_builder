package com.fpt.workflow.connector.repository;

import com.fpt.workflow.connector.domain.ConnectorActionVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectorActionVersionRepository
    extends JpaRepository<ConnectorActionVersion, UUID> {

  Optional<ConnectorActionVersion> findByConnectorActionIdAndVersionNo(
      UUID connectorActionId, int versionNo);

  List<ConnectorActionVersion> findAllByConnectorActionIdOrderByVersionNoDesc(
      UUID connectorActionId);

  @Query(
      "SELECT v FROM ConnectorActionVersion v "
          + "JOIN ConnectorAction a ON v.connectorActionId = a.id "
          + "JOIN ConnectorDefinition c ON a.connectorId = c.id "
          + "WHERE c.key = :connectorKey AND a.actionKey = :actionKey AND v.versionNo = :versionNo")
  Optional<ConnectorActionVersion> findByConnectorKeyAndActionKeyAndVersionNo(
      @Param("connectorKey") String connectorKey,
      @Param("actionKey") String actionKey,
      @Param("versionNo") int versionNo);

  @Query(
      "SELECT v FROM ConnectorActionVersion v "
          + "JOIN ConnectorAction a ON v.connectorActionId = a.id "
          + "JOIN ConnectorDefinition c ON a.connectorId = c.id "
          + "WHERE c.key = :connectorKey AND a.actionKey = :actionKey AND v.status = 'PUBLISHED' "
          + "ORDER BY v.versionNo DESC")
  List<ConnectorActionVersion> findAllPublished(
      @Param("connectorKey") String connectorKey, @Param("actionKey") String actionKey);
}
