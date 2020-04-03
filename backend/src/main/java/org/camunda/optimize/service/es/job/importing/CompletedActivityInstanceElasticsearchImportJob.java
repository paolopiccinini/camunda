/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.job.importing;

import org.camunda.optimize.dto.optimize.ImportRequestDto;
import org.camunda.optimize.dto.optimize.importing.FlowNodeEventDto;
import org.camunda.optimize.service.CamundaEventImportService;
import org.camunda.optimize.service.es.job.ElasticsearchImportJob;
import org.camunda.optimize.service.es.writer.CompletedActivityInstanceWriter;
import org.camunda.optimize.service.es.writer.ElasticsearchWriterUtil;

import java.util.ArrayList;
import java.util.List;

public class CompletedActivityInstanceElasticsearchImportJob extends ElasticsearchImportJob<FlowNodeEventDto> {

  private CompletedActivityInstanceWriter completedActivityInstanceWriter;
  private CamundaEventImportService camundaEventImportService;

  public CompletedActivityInstanceElasticsearchImportJob(CompletedActivityInstanceWriter completedActivityInstanceWriter,
                                                         CamundaEventImportService camundaEventImportService,
                                                         Runnable callback) {
    super(callback);
    this.completedActivityInstanceWriter = completedActivityInstanceWriter;
    this.camundaEventImportService = camundaEventImportService;
  }

  @Override
  protected void persistEntities(List<FlowNodeEventDto> newOptimizeEntities) {
    final List<ImportRequestDto> importRequests = new ArrayList<>();
    importRequests.addAll(completedActivityInstanceWriter.generateActivityInstanceImports(newOptimizeEntities));
    importRequests.addAll(camundaEventImportService.generateCompletedCamundaActivityEventsImports(newOptimizeEntities));
    ElasticsearchWriterUtil.executeImportRequestsAsBulk("Completed activity instances", importRequests);
  }

}
