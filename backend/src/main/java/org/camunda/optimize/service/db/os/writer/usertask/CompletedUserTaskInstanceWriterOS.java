/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.os.writer.usertask;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.optimize.dto.optimize.ImportRequestDto;
import org.camunda.optimize.dto.optimize.query.event.process.FlowNodeInstanceDto;
import org.camunda.optimize.service.db.DatabaseClient;
import org.camunda.optimize.service.db.os.OptimizeOpenSearchClient;
import org.camunda.optimize.service.db.writer.usertask.CompletedUserTaskInstanceWriter;
import org.camunda.optimize.service.util.configuration.condition.OpenSearchCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@AllArgsConstructor
@Conditional(OpenSearchCondition.class)
public class CompletedUserTaskInstanceWriterOS extends AbstractUserTaskWriterOS
    implements CompletedUserTaskInstanceWriter {
  private final OptimizeOpenSearchClient osClient;

  @Override
  protected String createInlineUpdateScript() {
    log.debug("Functionality not implemented for OpenSearch");
    return null;
  }

  @Override
  public List<ImportRequestDto> generateUserTaskImports(
      final List<FlowNodeInstanceDto> userTaskInstances) {
    return super.generateUserTaskImports(
        "completed user task instances", osClient, userTaskInstances);
  }

  @Override
  public List<ImportRequestDto> generateUserTaskImports(
      final String importItemName,
      final DatabaseClient databaseClient,
      final List<FlowNodeInstanceDto> userTaskInstances) {
    log.debug("Functionality not implemented for OpenSearch");
    return new ArrayList<>();
  }
}
