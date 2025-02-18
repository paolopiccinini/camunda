/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.rdbms.handlers;

import io.camunda.db.rdbms.write.domain.FlowNodeInstanceDbModel;
import io.camunda.db.rdbms.write.domain.FlowNodeInstanceDbModel.FlowNodeInstanceDbModelBuilder;
import io.camunda.db.rdbms.write.service.FlowNodeInstanceWriter;
import io.camunda.exporter.rdbms.RdbmsExportHandler;
import io.camunda.search.entities.FlowNodeInstanceEntity.FlowNodeState;
import io.camunda.search.entities.FlowNodeInstanceEntity.FlowNodeType;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.Intent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.util.DateUtil;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlowNodeExportHandler implements RdbmsExportHandler<ProcessInstanceRecordValue> {

  private static final Logger LOG = LoggerFactory.getLogger(FlowNodeExportHandler.class);

  private static final Set<Intent> FLOW_NODE_INTENT =
      Set.of(
          ProcessInstanceIntent.ELEMENT_ACTIVATING,
          ProcessInstanceIntent.ELEMENT_COMPLETED,
          ProcessInstanceIntent.ELEMENT_MIGRATED,
          ProcessInstanceIntent.ELEMENT_TERMINATED);

  private static final Set<BpmnElementType> UNHANDLED_BPMN_TYPES =
      Set.of(BpmnElementType.PROCESS, BpmnElementType.SEQUENCE_FLOW);

  private final FlowNodeInstanceWriter flowNodeInstanceWriter;

  public FlowNodeExportHandler(final FlowNodeInstanceWriter flowNodeInstanceWriter) {
    this.flowNodeInstanceWriter = flowNodeInstanceWriter;
  }

  @Override
  public boolean canExport(final Record<ProcessInstanceRecordValue> record) {
    return record.getValueType() == ValueType.PROCESS_INSTANCE
        && FLOW_NODE_INTENT.contains(record.getIntent())
        && !UNHANDLED_BPMN_TYPES.contains(record.getValue().getBpmnElementType());
  }

  @Override
  public void export(final Record<ProcessInstanceRecordValue> record) {
    final var value = record.getValue();
    if (record.getIntent() == ProcessInstanceIntent.ELEMENT_ACTIVATING) {
      flowNodeInstanceWriter.create(map(record, value));
    } else if (record.getIntent() == ProcessInstanceIntent.ELEMENT_MIGRATED) {
      flowNodeInstanceWriter.update(map(record, value));
    } else if (record.getIntent() == ProcessInstanceIntent.ELEMENT_COMPLETED) {
      flowNodeInstanceWriter.finish(
          record.getKey(),
          FlowNodeState.COMPLETED,
          DateUtil.toOffsetDateTime(record.getTimestamp()));
    } else if (record.getIntent() == ProcessInstanceIntent.ELEMENT_TERMINATED) {
      flowNodeInstanceWriter.finish(
          record.getKey(),
          FlowNodeState.TERMINATED,
          DateUtil.toOffsetDateTime(record.getTimestamp()));
    }
  }

  private static FlowNodeInstanceDbModel map(
      final Record<ProcessInstanceRecordValue> record, final ProcessInstanceRecordValue value) {
    return new FlowNodeInstanceDbModelBuilder()
        .flowNodeInstanceKey(record.getKey())
        .flowNodeId(value.getElementId())
        .processInstanceKey(value.getProcessInstanceKey())
        .processDefinitionKey(value.getProcessDefinitionKey())
        .processDefinitionId(value.getBpmnProcessId())
        .tenantId(value.getTenantId())
        .state(FlowNodeState.ACTIVE)
        .startDate(DateUtil.toOffsetDateTime(record.getTimestamp()))
        .type(mapFlowNodeType(value))
        .partitionId(record.getPartitionId())
        .treePath(buildTreePath(record))
        .build();
  }

  private static FlowNodeType mapFlowNodeType(final ProcessInstanceRecordValue recordValue) {
    if (recordValue.getBpmnElementType() == null) {
      return FlowNodeType.UNSPECIFIED;
    }
    try {
      return FlowNodeType.valueOf(recordValue.getBpmnElementType().name());
    } catch (final IllegalArgumentException ex) {
      return FlowNodeType.UNKNOWN;
    }
  }

  protected static String buildTreePath(final Record<ProcessInstanceRecordValue> record) {
    final ProcessInstanceRecordValue value = record.getValue();
    if (value.getElementInstancePath() != null && !value.getElementInstancePath().isEmpty()) {
      // Build the intra treePath in the format:
      // <pre>
      //   <processInstanceKey>/<flowNodeInstanceKey>/.../<flowNodeInstanceKey>
      // </pre>
      //
      // Where upper level flowNodeInstanceKeys are normally subprocess(es) or multi-instance
      // bodies.
      // This is an intra tree path that shows position of flow node instance inside one process
      // instance.
      // We take last entry, as in intra path we're not interested in upper level process instance
      // scope hierarchies.
      final List<String> treePathEntries =
          value.getElementInstancePath().getLast().stream().map(String::valueOf).toList();
      return String.join("/", treePathEntries);
    } else {
      LOG.warn(
          "No elementInstancePath is provided for flow node instance id: {}. TreePath will be set to default value.",
          record.getKey());
      return value.getProcessInstanceKey() + "/" + record.getKey();
    }
  }
}
