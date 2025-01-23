/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.handlers;

import io.camunda.exporter.exceptions.PersistenceException;
import io.camunda.exporter.store.BatchRequest;
import io.camunda.webapps.schema.descriptors.usermanagement.index.TenantIndex;
import io.camunda.webapps.schema.entities.usermanagement.TenantMemberEntity;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.TenantIntent;
import io.camunda.zeebe.protocol.record.value.TenantRecordValue;
import java.util.List;

public class TenantEntityRemovedHandler
    implements ExportHandler<TenantMemberEntity, TenantRecordValue> {

  private final String indexName;

  public TenantEntityRemovedHandler(final String indexName) {
    this.indexName = indexName;
  }

  @Override
  public ValueType getHandledValueType() {
    return ValueType.TENANT;
  }

  @Override
  public Class<TenantMemberEntity> getEntityType() {
    return TenantMemberEntity.class;
  }

  @Override
  public boolean handlesRecord(final Record<TenantRecordValue> record) {
    return getHandledValueType().equals(record.getValueType())
        && TenantIntent.ENTITY_REMOVED.equals(record.getIntent());
  }

  @Override
  public List<String> generateIds(final Record<TenantRecordValue> record) {
    final var tenantRecord = record.getValue();
    return List.of(
        TenantMemberEntity.getChildKey(tenantRecord.getTenantId(), tenantRecord.getEntityId()));
  }

  @Override
  public TenantMemberEntity createNewEntity(final String id) {
    return new TenantMemberEntity().setId(id);
  }

  @Override
  public void updateEntity(
      final Record<TenantRecordValue> record, final TenantMemberEntity entity) {
    final TenantRecordValue value = record.getValue();
    final var joinRelation = TenantIndex.JOIN_RELATION_FACTORY.createChild(value.getTenantId());
    entity.setMemberId(value.getEntityId()).setJoin(joinRelation);
  }

  @Override
  public void flush(final TenantMemberEntity entity, final BatchRequest batchRequest)
      throws PersistenceException {
    batchRequest.deleteWithRouting(
        indexName, entity.getId(), String.valueOf(entity.getJoin().parent()));
  }

  @Override
  public String getIndexName() {
    return indexName;
  }
}
