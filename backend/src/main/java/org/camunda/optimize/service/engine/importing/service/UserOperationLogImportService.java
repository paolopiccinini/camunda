package org.camunda.optimize.service.engine.importing.service;

import org.camunda.optimize.dto.engine.UserOperationLogEntryEngineDto;
import org.camunda.optimize.dto.optimize.importing.UserOperationLogEntryDto;
import org.camunda.optimize.rest.engine.EngineContext;
import org.camunda.optimize.service.es.ElasticsearchImportJobExecutor;
import org.camunda.optimize.service.es.job.ElasticsearchImportJob;
import org.camunda.optimize.service.es.job.importing.UserOperationEntryElasticsearchImportJob;
import org.camunda.optimize.service.es.writer.UserOperationsLogEntryWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class UserOperationLogImportService {
  private static final Logger logger = LoggerFactory.getLogger(UserOperationLogImportService.class);

  private final ElasticsearchImportJobExecutor elasticsearchImportJobExecutor;
  private final EngineContext engineContext;
  private final UserOperationsLogEntryWriter userOperationsLogEntryWriter;

  public UserOperationLogImportService(final UserOperationsLogEntryWriter userOperationsLogEntryWriter,
                                       final ElasticsearchImportJobExecutor elasticsearchImportJobExecutor,
                                       final EngineContext engineContext) {
    this.elasticsearchImportJobExecutor = elasticsearchImportJobExecutor;
    this.engineContext = engineContext;
    this.userOperationsLogEntryWriter = userOperationsLogEntryWriter;
  }

  public void executeImport(final List<UserOperationLogEntryEngineDto> pageOfEngineEntities) {
    logger.trace("Importing user operation log entries from engine...");

    final boolean newDataIsAvailable = !pageOfEngineEntities.isEmpty();
    if (newDataIsAvailable) {
      final List<UserOperationLogEntryDto> newOptimizeEntities = mapEngineEntitiesToOptimizeEntities(pageOfEngineEntities);
      final ElasticsearchImportJob<UserOperationLogEntryDto> elasticsearchImportJob =
        createElasticsearchImportJob(newOptimizeEntities);
      addElasticsearchImportJobToQueue(elasticsearchImportJob);
    }
  }

  private void addElasticsearchImportJobToQueue(final ElasticsearchImportJob elasticsearchImportJob) {
    try {
      elasticsearchImportJobExecutor.executeImportJob(elasticsearchImportJob);
    } catch (InterruptedException e) {
      logger.error("Was interrupted while trying to add new job to Elasticsearch import queue.", e);
    }
  }

  private List<UserOperationLogEntryDto> mapEngineEntitiesToOptimizeEntities(final List<UserOperationLogEntryEngineDto> engineEntities) {
    List<UserOperationLogEntryDto> list = new ArrayList<>();
    for (UserOperationLogEntryEngineDto engineEntity : engineEntities) {
      UserOperationLogEntryDto userOperationLogEntry = mapEngineEntityToOptimizeEntity(engineEntity);
      list.add(userOperationLogEntry);
    }
    return list;
  }

  private ElasticsearchImportJob<UserOperationLogEntryDto> createElasticsearchImportJob(final List<UserOperationLogEntryDto> userTasks) {
    final UserOperationEntryElasticsearchImportJob importJob = new UserOperationEntryElasticsearchImportJob(
      userOperationsLogEntryWriter
    );
    importJob.setEntitiesToImport(userTasks);
    return importJob;
  }

  private UserOperationLogEntryDto mapEngineEntityToOptimizeEntity(final UserOperationLogEntryEngineDto engineEntity) {
    final UserOperationLogEntryDto userTaskInstanceDto = new UserOperationLogEntryDto(
      engineEntity.getId(),
      engineEntity.getTaskId(),
      engineEntity.getUserId(),
      engineEntity.getTimestamp(),
      engineEntity.getOperationType(),
      engineEntity.getProperty(),
      engineEntity.getOrgValue(),
      engineEntity.getNewValue(),
      engineContext.getEngineAlias()
    );
    return userTaskInstanceDto;
  }

}
