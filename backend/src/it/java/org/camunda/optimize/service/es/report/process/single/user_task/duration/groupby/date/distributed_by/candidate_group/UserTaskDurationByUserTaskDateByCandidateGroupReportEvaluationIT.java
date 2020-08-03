/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.report.process.single.user_task.duration.groupby.date.distributed_by.candidate_group;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.assertj.core.groups.Tuple;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.optimize.dto.engine.definition.ProcessDefinitionEngineDto;
import org.camunda.optimize.dto.optimize.query.report.single.configuration.DistributedBy;
import org.camunda.optimize.dto.optimize.query.report.single.configuration.UserTaskDurationTime;
import org.camunda.optimize.dto.optimize.query.report.single.filter.data.FilterOperator;
import org.camunda.optimize.dto.optimize.query.report.single.group.GroupByDateUnit;
import org.camunda.optimize.dto.optimize.query.report.single.process.ProcessReportDataDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.ProcessFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.util.ProcessFilterBuilder;
import org.camunda.optimize.dto.optimize.query.report.single.process.group.ProcessGroupByType;
import org.camunda.optimize.dto.optimize.query.report.single.process.group.value.DateGroupByValueDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.view.ProcessViewDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.view.ProcessViewEntity;
import org.camunda.optimize.dto.optimize.query.report.single.process.view.ProcessViewProperty;
import org.camunda.optimize.dto.optimize.query.report.single.result.hyper.HyperMapResultEntryDto;
import org.camunda.optimize.dto.optimize.query.report.single.result.hyper.MapResultEntryDto;
import org.camunda.optimize.dto.optimize.query.report.single.result.hyper.ReportHyperMapResultDto;
import org.camunda.optimize.dto.optimize.query.sorting.SortOrder;
import org.camunda.optimize.dto.optimize.query.sorting.ReportSortingDto;
import org.camunda.optimize.dto.optimize.rest.report.AuthorizedProcessReportEvaluationResultDto;
import org.camunda.optimize.rest.engine.dto.ProcessInstanceEngineDto;
import org.camunda.optimize.service.es.report.process.AbstractProcessDefinitionIT;
import org.camunda.optimize.service.es.report.util.HyperMapAsserter;
import org.camunda.optimize.test.util.ProcessReportDataType;
import org.camunda.optimize.test.util.TemplatedProcessReportDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.collect.Lists.newArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.camunda.optimize.dto.optimize.ReportConstants.ALL_VERSIONS;
import static org.camunda.optimize.dto.optimize.query.report.single.filter.data.FilterOperator.IN;
import static org.camunda.optimize.dto.optimize.query.report.single.filter.data.FilterOperator.NOT_IN;
import static org.camunda.optimize.dto.optimize.query.sorting.ReportSortingDto.SORT_BY_KEY;
import static org.camunda.optimize.dto.optimize.query.sorting.ReportSortingDto.SORT_BY_VALUE;
import static org.camunda.optimize.service.es.filter.DateHistogramBucketLimiterUtil.mapToChronoUnit;
import static org.camunda.optimize.test.it.extension.TestEmbeddedCamundaOptimize.DEFAULT_PASSWORD;
import static org.camunda.optimize.test.it.extension.TestEmbeddedCamundaOptimize.DEFAULT_USERNAME;
import static org.camunda.optimize.test.util.DateModificationHelper.truncateToStartOfUnit;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.NUMBER_OF_DATA_POINTS_FOR_AUTOMATIC_INTERVAL_SELECTION;

public abstract class UserTaskDurationByUserTaskDateByCandidateGroupReportEvaluationIT
  extends AbstractProcessDefinitionIT {

  @BeforeEach
  public void init() {
    engineIntegrationExtension.createGroup(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.createGroup(SECOND_CANDIDATE_GROUP);
  }

  @Test
  public void reportEvaluationForOneProcess() {
    // given
    ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    final ProcessInstanceEngineDto processInstance =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();

    final Double expectedDuration = 20.;
    changeDuration(processInstance, expectedDuration);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final AuthorizedProcessReportEvaluationResultDto<ReportHyperMapResultDto> evaluationResponse =
      reportClient.evaluateHyperMapReport(reportData);

    // then
    final ProcessReportDataDto resultReportDataDto = evaluationResponse.getReportDefinition().getData();
    assertThat(resultReportDataDto.getProcessDefinitionKey()).isEqualTo(processDefinition.getKey());
    assertThat(resultReportDataDto.getDefinitionVersions()).containsExactly(processDefinition.getVersionAsString());
    assertThat(resultReportDataDto.getView())
      .isEqualToComparingFieldByField(new ProcessViewDto(ProcessViewEntity.USER_TASK, ProcessViewProperty.DURATION));
    assertThat(resultReportDataDto.getGroupBy()).isNotNull();
    assertThat(resultReportDataDto.getGroupBy().getType()).isEqualTo(getGroupByType());
    assertThat(resultReportDataDto.getGroupBy().getValue())
      .extracting(DateGroupByValueDto.class::cast)
      .extracting(DateGroupByValueDto::getUnit)
      .isEqualTo(GroupByDateUnit.DAY);
    assertThat(resultReportDataDto.getConfiguration().getDistributedBy()).isEqualTo(DistributedBy.CANDIDATE_GROUP);

    final ReportHyperMapResultDto result = evaluationResponse.getResult();
    ZonedDateTime startOfToday = truncateToStartOfUnit(OffsetDateTime.now(), ChronoUnit.DAYS);
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(1L)
      .processInstanceCountWithoutFilters(1L)
      .groupByContains(localDateTimeToString(startOfToday))
        .distributedByContains(FIRST_CANDIDATE_GROUP, expectedDuration)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void resultIsSortedInDescendingOrder() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(3));
    changeDuration(processInstance1, USER_TASK_1, 30.);
    changeUserTaskDate(processInstance1, USER_TASK_2, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_2, 10.);

    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeUserTaskDate(processInstance2, USER_TASK_2, referenceDate.minusDays(4));
    changeDuration(processInstance2, USER_TASK_2, 40.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 10.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 20.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(3)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 30.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(4)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 40.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void testCustomOrderOnResultKeyIsApplied() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(3));
    changeDuration(processInstance1, USER_TASK_1, 30.);
    changeUserTaskDate(processInstance1, USER_TASK_2, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_2, 10.);

    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeUserTaskDate(processInstance2, USER_TASK_2, referenceDate.minusDays(4));
    changeDuration(processInstance2, USER_TASK_2, 40.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    reportData.getConfiguration().setSorting(new ReportSortingDto(SORT_BY_KEY, SortOrder.ASC));
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
        .distributedByContains(SECOND_CANDIDATE_GROUP, 10.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 20.)
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(3)))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 30.)
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(4)))
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
        .distributedByContains(SECOND_CANDIDATE_GROUP, 40.)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void testCustomOrderOnResultValueIsApplied() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(3));
    changeDuration(processInstance1, USER_TASK_1, 10.);
    changeUserTaskDate(processInstance1, USER_TASK_2, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_2, 10.);

    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeUserTaskDate(processInstance2, USER_TASK_2, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_2, 50.);

    ProcessInstanceEngineDto processInstance3 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance3, USER_TASK_1, referenceDate.minusDays(3));
    changeDuration(processInstance3, USER_TASK_1, 30.);
    changeUserTaskDate(processInstance3, USER_TASK_2, referenceDate.minusDays(3));
    changeDuration(processInstance3, USER_TASK_2, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    reportData.getConfiguration().setSorting(new ReportSortingDto(SORT_BY_VALUE, SortOrder.DESC));
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(3L)
      .processInstanceCountWithoutFilters(3L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 10.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 50.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 20.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(3)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 30.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 20.)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void multipleBuckets_noFilter_resultLimitedByConfig() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(3));
    changeUserTaskDate(processInstance1, USER_TASK_2, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_2, 10.);

    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeUserTaskDate(processInstance2, USER_TASK_2, referenceDate.minusDays(4));

    importAllEngineEntitiesFromScratch();

    embeddedOptimizeExtension.getConfigurationService().setEsAggregationBucketLimit(2);

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .isComplete(false)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 10.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 20.)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void userTasksStartedAtSameIntervalAreGroupedTogetherAndMeanDurationUsed() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_1, 5.);
    changeUserTaskDate(processInstance1, USER_TASK_2, referenceDate.minusDays(2));
    changeDuration(processInstance1, USER_TASK_2, 100.);

    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(1));
    changeDuration(processInstance2, USER_TASK_1, 15.);
    changeUserTaskDate(processInstance2, USER_TASK_2, referenceDate.minusDays(2));
    changeDuration(processInstance2, USER_TASK_2, 300.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 10.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 200.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void emptyIntervalBetweenTwoUserTaskDates() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    ProcessInstanceEngineDto processInstance =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeUserTaskDate(processInstance, USER_TASK_1, referenceDate.minusDays(1));
    changeDuration(processInstance, USER_TASK_1, 10.);
    changeUserTaskDate(processInstance, USER_TASK_2, referenceDate.minusDays(3));
    changeDuration(processInstance, USER_TASK_2, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(1L)
      .processInstanceCountWithoutFilters(1L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 10.)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(2)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, null)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(3)))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 30.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, null)
      .doAssert(result);
    // @formatter:on
  }

  @ParameterizedTest
  @MethodSource("staticGroupByDateUnits")
  public void countGroupByDateUnit(final GroupByDateUnit groupByDateUnit) {
    // given
    final ChronoUnit groupByUnitAsChrono = mapToChronoUnit(groupByDateUnit);
    final int groupingCount = 5;
    OffsetDateTime referenceDate = OffsetDateTime.now();

    ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    List<ProcessInstanceEngineDto> processInstanceDtos = IntStream.range(0, groupingCount)
      .mapToObj(i -> {
        ProcessInstanceEngineDto processInstanceEngineDto =
          engineIntegrationExtension.startProcessInstance(processDefinition.getId());
        processInstanceEngineDto.setProcessDefinitionKey(processDefinition.getKey());
        processInstanceEngineDto.setProcessDefinitionVersion(String.valueOf(processDefinition.getVersion()));
        return processInstanceEngineDto;
      })
      .collect(Collectors.toList());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    updateUserTaskDateAndDuration(processInstanceDtos, referenceDate, groupByUnitAsChrono);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processDefinition, groupByDateUnit);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // we need to do the first assert here so that every loop has access to the the groupByAdder
    // of the previous loop.
    HyperMapAsserter.GroupByAdder groupByAdder = HyperMapAsserter.asserter()
      .processInstanceCount(groupingCount)
      .processInstanceCountWithoutFilters(groupingCount)
      .groupByContains(groupedByDateAsString(referenceDate.minus(0, groupByUnitAsChrono), groupByUnitAsChrono))
      .distributedByContains(FIRST_CANDIDATE_GROUP, 10.);

    for (int i = 1; i < groupingCount; i++) {
      groupByAdder = groupByAdder
        .groupByContains(groupedByDateAsString(referenceDate.minus(i, groupByUnitAsChrono), groupByUnitAsChrono))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 10.);
    }
    groupByAdder.doAssert(result);
  }

  @Test
  public void otherProcessDefinitionsDoNotAffectResult() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition1 = deployOneUserTaskDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition1.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeUserTaskDate(processInstance1, USER_TASK_1, referenceDate.minusDays(1));
    changeDuration(processInstance1, USER_TASK_1, 10.);

    ProcessDefinitionEngineDto processDefinition2 = deployOneUserTaskDefinition();
    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition2.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeUserTaskDate(processInstance2, USER_TASK_1, referenceDate.minusDays(1));
    changeDuration(processInstance2, USER_TASK_1, 200.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition1);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(1L)
      .processInstanceCountWithoutFilters(1L)
      .groupByContains(groupedByDayDateAsString(referenceDate.minusDays(1)))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 10.)
      .doAssert(result);
    // @formatter:on
  }

  @Test
  public void reportEvaluationSingleBucketFilteredBySingleTenant() {
    // given
    final String tenantId1 = "tenantId1";
    final String tenantId2 = "tenantId2";
    final List<String> selectedTenants = newArrayList(tenantId1);
    final String processKey = deployAndStartMultiTenantUserTaskProcess(
      newArrayList(null, tenantId1, tenantId2)
    );

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processKey, "1", GroupByDateUnit.DAY);
    reportData.setTenantIds(selectedTenants);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    assertThat(result.getInstanceCount()).isEqualTo((long) selectedTenants.size());
  }

  @Test
  public void filterWorks() {
    // given
    final OffsetDateTime referenceDate = OffsetDateTime.now();
    ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    final ProcessInstanceEngineDto processInstance =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeDuration(processInstance, USER_TASK_1, 10.);
    engineIntegrationExtension.startProcessInstance(processDefinition.getId());

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final List<ProcessFilterDto<?>> processFilterDtoList = ProcessFilterBuilder.filter()
      .completedInstancesOnly().add().buildList();
    reportData.setFilter(processFilterDtoList);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(1L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(referenceDate))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 10.)
      .doAssert(result);
    // @formatter:on
  }

  public static Stream<Arguments> assigneeFilterScenarios() {
    return Stream.of(
      Arguments.of(IN, new String[]{SECOND_USER}, Lists.newArrayList(Tuple.tuple(SECOND_CANDIDATE_GROUP, 10.))),
      Arguments.of(
        IN,
        new String[]{DEFAULT_USERNAME, SECOND_USER},
        Lists.newArrayList(Tuple.tuple(FIRST_CANDIDATE_GROUP, 10.), Tuple.tuple(SECOND_CANDIDATE_GROUP, 10.))
      ),
      Arguments.of(NOT_IN, new String[]{SECOND_USER}, Lists.newArrayList(Tuple.tuple(FIRST_CANDIDATE_GROUP, 10.))),
      Arguments.of(NOT_IN, new String[]{DEFAULT_USERNAME, SECOND_USER}, Lists.newArrayList())
    );
  }

  @ParameterizedTest
  @MethodSource("assigneeFilterScenarios")
  @SuppressWarnings("unchecked")
  public void filterByAssigneeOnlyCountsUserTaskWithThatAssignee(final FilterOperator filterOperator,
                                                                 final String[] filterValues,
                                                                 final List<Tuple> expectedResult) {
    // given
    engineIntegrationExtension.addUser(SECOND_USER, SECOND_USERS_PASSWORD);
    engineIntegrationExtension.grantAllAuthorizations(SECOND_USER);

    final ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    final ProcessInstanceEngineDto processInstanceDto = engineIntegrationExtension
      .startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks(
      DEFAULT_USERNAME, DEFAULT_PASSWORD, processInstanceDto.getId()
    );
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(SECOND_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks(
      SECOND_USER, SECOND_USERS_PASSWORD, processInstanceDto.getId()
    );
    changeDuration(processInstanceDto, USER_TASK_1, 10.);
    changeDuration(processInstanceDto, USER_TASK_2, 10.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final List<ProcessFilterDto<?>> inclusiveAssigneeFilter = ProcessFilterBuilder
      .filter().assignee().ids(filterValues).operator(filterOperator).add().buildList();
    reportData.setFilter(inclusiveAssigneeFilter);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    assertThat(result.getData())
      .flatExtracting(HyperMapResultEntryDto::getValue)
      .extracting(MapResultEntryDto::getKey, MapResultEntryDto::getValue)
      .containsExactlyInAnyOrderElementsOf(expectedResult);
  }

  public static Stream<Arguments> candidateGroupFilterScenarios() {
    return Stream.of(
      Arguments.of(
        IN, new String[]{SECOND_CANDIDATE_GROUP}, Lists.newArrayList(Tuple.tuple(SECOND_CANDIDATE_GROUP, 10.))
      ),
      Arguments.of(
        IN,
        new String[]{FIRST_CANDIDATE_GROUP, SECOND_CANDIDATE_GROUP},
        Lists.newArrayList(Tuple.tuple(FIRST_CANDIDATE_GROUP, 10.), Tuple.tuple(SECOND_CANDIDATE_GROUP, 10.))
      ),
      Arguments.of(
        NOT_IN, new String[]{SECOND_CANDIDATE_GROUP}, Lists.newArrayList(Tuple.tuple(FIRST_CANDIDATE_GROUP, 10.))
      ),
      Arguments.of(NOT_IN, new String[]{FIRST_CANDIDATE_GROUP, SECOND_CANDIDATE_GROUP}, Lists.newArrayList())
    );
  }

  @ParameterizedTest
  @MethodSource("candidateGroupFilterScenarios")
  @SuppressWarnings("unchecked")
  public void filterByCandidateGroupOnlyCountsUserTaskWithThatCandidateGroup(final FilterOperator filterOperator,
                                                                             final String[] filterValues,
                                                                             final List<Tuple> expectedResult) {
    // given
    final ProcessDefinitionEngineDto processDefinition = deployTwoUserTasksDefinition();
    final ProcessInstanceEngineDto processInstanceDto = engineIntegrationExtension
      .startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(SECOND_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeDuration(processInstanceDto, USER_TASK_1, 10.);
    changeDuration(processInstanceDto, USER_TASK_2, 10.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(processDefinition);
    final List<ProcessFilterDto<?>> inclusiveAssigneeFilter = ProcessFilterBuilder
      .filter().candidateGroups().ids(filterValues).operator(filterOperator).add().buildList();
    reportData.setFilter(inclusiveAssigneeFilter);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    assertThat(result.getData())
      .flatExtracting(HyperMapResultEntryDto::getValue)
      .extracting(MapResultEntryDto::getKey, MapResultEntryDto::getValue)
      .containsExactlyInAnyOrderElementsOf(expectedResult);
  }

  @Test
  public void automaticIntervalSelection_simpleSetup() {
    // given
    final ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    ProcessInstanceEngineDto processInstance3 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    Map<String, OffsetDateTime> updates = new HashMap<>();
    OffsetDateTime startOfToday = OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS);
    updates.put(processInstance1.getId(), startOfToday);
    updates.put(processInstance2.getId(), startOfToday);
    updates.put(processInstance3.getId(), startOfToday.minusDays(1));
    changeUserTaskDates(updates);
    changeDuration(processInstance1, USER_TASK_1, 10.);
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeDuration(processInstance3, USER_TASK_1, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processDefinition, GroupByDateUnit.AUTOMATIC);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    final List<HyperMapResultEntryDto> resultData = result.getData();
    assertThat(resultData).hasSize(NUMBER_OF_DATA_POINTS_FOR_AUTOMATIC_INTERVAL_SELECTION);
    assertFirstValueEquals(resultData, 15.);
    assertLastValueEquals(resultData, 30.);
  }

  @Test
  public void automaticIntervalSelection_takesAllUserTasksIntoAccount() {
    //given
    final ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    ProcessInstanceEngineDto processInstance3 =
      engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    Map<String, OffsetDateTime> updates = new HashMap<>();
    OffsetDateTime startOfToday = OffsetDateTime.now().truncatedTo(ChronoUnit.DAYS);
    updates.put(processInstance1.getId(), startOfToday);
    updates.put(processInstance2.getId(), startOfToday.plusDays(2));
    updates.put(processInstance3.getId(), startOfToday.plusDays(5));
    changeUserTaskDates(updates);
    changeDuration(processInstance1, USER_TASK_1, 10.);
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeDuration(processInstance3, USER_TASK_1, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processDefinition, GroupByDateUnit.AUTOMATIC);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    final List<HyperMapResultEntryDto> resultData = result.getData();
    assertThat(resultData).hasSize(NUMBER_OF_DATA_POINTS_FOR_AUTOMATIC_INTERVAL_SELECTION);
    assertFirstValueEquals(resultData, 30.);
    assertLastValueEquals(resultData, 10.);
    final int sumOfAllValues = resultData.stream()
      .map(HyperMapResultEntryDto::getValue)
      .flatMap(List::stream)
      .filter(Objects::nonNull)
      .map(MapResultEntryDto::getValue)
      .filter(Objects::nonNull)
      .mapToInt(Double::intValue).sum();
    assertThat(sumOfAllValues).isEqualTo(60);
  }

  @Test
  public void automaticIntervalSelection_forNoData() {
    // given
    ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processDefinition, GroupByDateUnit.AUTOMATIC);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    final List<HyperMapResultEntryDto> resultData = result.getData();
    assertThat(resultData).isEmpty();
  }

  @Test
  public void automaticIntervalSelection_forOneDataPoint() {
    // given there is only one data point
    final ProcessDefinitionEngineDto processDefinition = deployOneUserTaskDefinition();
    engineIntegrationExtension.startProcessInstance(processDefinition.getId());
    engineIntegrationExtension.finishAllRunningUserTasks();

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createReportData(processDefinition, GroupByDateUnit.AUTOMATIC);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then the single data point should be grouped by month
    final List<HyperMapResultEntryDto> resultData = result.getData();
    ZonedDateTime nowStrippedToMonth = truncateToStartOfUnit(OffsetDateTime.now(), ChronoUnit.MONTHS);
    String nowStrippedToMonthAsString = localDateTimeToString(nowStrippedToMonth);
    assertThat(resultData).hasSize(1);
    assertThat(resultData).first().extracting(HyperMapResultEntryDto::getKey).isEqualTo(nowStrippedToMonthAsString);
  }

  @ParameterizedTest
  @MethodSource("multiVersionArguments")
  public void multipleVersionsRespectLatestNodesWhereLatestHasMoreFlowNodes(final List<String> definitionVersionsThatSpanMultipleDefinitions) {
    // given
    ProcessDefinitionEngineDto firstDefinition = deployOneUserTaskDefinition();
    final ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(firstDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeDuration(processInstance1, USER_TASK_1, 10.);

    ProcessDefinitionEngineDto latestDefinition = deployTwoUserTasksDefinition();
    final ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(latestDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeDuration(processInstance2, USER_TASK_2, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(latestDefinition);
    reportData.setProcessDefinitionVersions(definitionVersionsThatSpanMultipleDefinitions);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(OffsetDateTime.now()))
        .distributedByContains(SECOND_CANDIDATE_GROUP, 30.)
        .distributedByContains(FIRST_CANDIDATE_GROUP, 15.)
      .doAssert(result);
    // @formatter:on
  }

  @ParameterizedTest
  @MethodSource("multiVersionArguments")
  public void multipleVersionsRespectLatestNodesWhereLatestHasFewerFlowNodes(final List<String> definitionVersionsThatSpanMultipleDefinitions) {
    // given
    ProcessDefinitionEngineDto firstDefinition = deployTwoUserTasksDefinition();
    final ProcessInstanceEngineDto processInstance1 =
      engineIntegrationExtension.startProcessInstance(firstDefinition.getId());
    finishTwoUserTasksWithDifferentCandidateGroups();
    changeDuration(processInstance1, USER_TASK_1, 10.);

    ProcessDefinitionEngineDto latestDefinition = deployOneUserTaskDefinition();
    final ProcessInstanceEngineDto processInstance2 =
      engineIntegrationExtension.startProcessInstance(latestDefinition.getId());
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    changeDuration(processInstance2, USER_TASK_1, 20.);
    changeDuration(processInstance2, USER_TASK_2, 30.);

    importAllEngineEntitiesFromScratch();

    // when
    final ProcessReportDataDto reportData = createGroupedByDayReport(latestDefinition);
    reportData.setProcessDefinitionVersions(definitionVersionsThatSpanMultipleDefinitions);
    final ReportHyperMapResultDto result = reportClient.evaluateHyperMapReport(reportData).getResult();

    // then
    // @formatter:off
    HyperMapAsserter.asserter()
      .processInstanceCount(2L)
      .processInstanceCountWithoutFilters(2L)
      .groupByContains(groupedByDayDateAsString(OffsetDateTime.now()))
        .distributedByContains(FIRST_CANDIDATE_GROUP, 15.)
      .doAssert(result);
    // @formatter:on
  }

  private static Stream<List<String>> multiVersionArguments() {
    return Stream.of(
      Arrays.asList("1", "2"),
      Collections.singletonList(ALL_VERSIONS)
    );
  }

  private void assertLastValueEquals(final List<HyperMapResultEntryDto> resultData, final Double expected) {
    assertThat(resultData).last().extracting(HyperMapResultEntryDto::getValue)
      .extracting(e -> e.get(0))
      .extracting(MapResultEntryDto::getValue)
      .isEqualTo(expected);
  }

  private void assertFirstValueEquals(final List<HyperMapResultEntryDto> resultData, final Double expected) {
    assertThat(resultData).first().extracting(HyperMapResultEntryDto::getValue)
      .extracting(e -> e.get(0))
      .extracting(MapResultEntryDto::getValue)
      .isEqualTo(expected);
  }

  private void updateUserTaskDateAndDuration(List<ProcessInstanceEngineDto> procInsts,
                                             OffsetDateTime now,
                                             ChronoUnit unit) {
    Map<String, OffsetDateTime> idToNewStartDate = new HashMap<>();
    IntStream.range(0, procInsts.size())
      .forEach(i -> {
        String id = procInsts.get(i).getId();
        OffsetDateTime newStartDate = now.minus(i, unit);
        idToNewStartDate.put(id, newStartDate);
      });
    changeUserTaskDates(idToNewStartDate);
    procInsts.forEach(processInstance -> changeDuration(processInstance, 10.));
  }

  protected ProcessReportDataDto createReportData(final String processDefinitionKey, final String version,
                                                  final GroupByDateUnit groupByDateUnit) {
    return createReportData(processDefinitionKey, ImmutableList.of(version), groupByDateUnit);
  }

  protected ProcessReportDataDto createReportData(final String processDefinitionKey, final List<String> versions,
                                                  final GroupByDateUnit groupByDateUnit) {
    return TemplatedProcessReportDataBuilder
      .createReportData()
      .setProcessDefinitionKey(processDefinitionKey)
      .setProcessDefinitionVersions(versions)
      .setUserTaskDurationTime(getUserTaskDurationTime())
      .setReportDataType(getReportDataType())
      .setDateInterval(groupByDateUnit)
      .build();
  }

  protected ProcessReportDataDto createGroupedByDayReport(final ProcessDefinitionEngineDto processDefinition) {
    return createReportData(processDefinition, GroupByDateUnit.DAY);
  }

  protected ProcessReportDataDto createReportData(final ProcessDefinitionEngineDto processDefinition,
                                                  final GroupByDateUnit groupByDateUnit) {
    return createReportData(
      processDefinition.getKey(),
      String.valueOf(processDefinition.getVersion()),
      groupByDateUnit
    );
  }

  private String deployAndStartMultiTenantUserTaskProcess(final List<String> deployedTenants) {
    final String processKey = "multiTenantProcess";
    deployedTenants.stream()
      .filter(Objects::nonNull)
      .forEach(tenantId -> engineIntegrationExtension.createTenant(tenantId));
    deployedTenants
      .forEach(tenant -> {
        final ProcessDefinitionEngineDto processDefinitionEngineDto = deployOneUserTaskDefinition(processKey, tenant);
        engineIntegrationExtension.startProcessInstance(processDefinitionEngineDto.getId());
      });
    return processKey;
  }

  protected ProcessDefinitionEngineDto deployOneUserTaskDefinition() {
    return deployOneUserTaskDefinition("aProcess", null);
  }

  private ProcessDefinitionEngineDto deployOneUserTaskDefinition(String key, String tenantId) {
    // @formatter:off
    BpmnModelInstance modelInstance = Bpmn.createExecutableProcess(key)
      .startEvent(START_EVENT)
      .userTask(USER_TASK_1)
        .name(USER_TASK_1_NAME)
      .endEvent(END_EVENT)
      .done();
    // @formatter:on
    return engineIntegrationExtension.deployProcessAndGetProcessDefinition(modelInstance, tenantId);
  }

  protected ProcessDefinitionEngineDto deployTwoUserTasksDefinition() {
    // @formatter:off
    BpmnModelInstance modelInstance = Bpmn.createExecutableProcess("aProcess")
      .startEvent(START_EVENT)
      .userTask(USER_TASK_1)
        .name(USER_TASK_1_NAME)
      .userTask(USER_TASK_2)
        .name(USER_TASK_2_NAME)
      .endEvent(END_EVENT)
      .done();
    // @formatter:on
    return engineIntegrationExtension.deployProcessAndGetProcessDefinition(modelInstance);
  }

  private void finishTwoUserTasksWithDifferentCandidateGroups() {
    // finish user task 1 with first candidate group
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(FIRST_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
    // finish user task 2 with second candidate group
    engineIntegrationExtension.addCandidateGroupForAllRunningUserTasks(SECOND_CANDIDATE_GROUP);
    engineIntegrationExtension.finishAllRunningUserTasks();
  }

  protected String groupedByDayDateAsString(final OffsetDateTime referenceDate) {
    return groupedByDateAsString(referenceDate, ChronoUnit.DAYS);
  }

  private String groupedByDateAsString(final OffsetDateTime referenceDate, final ChronoUnit chronoUnit) {
    return localDateTimeToString(truncateToStartOfUnit(referenceDate, chronoUnit));
  }

  protected abstract void changeDuration(final ProcessInstanceEngineDto processInstanceDto,
                                         final String userTaskKey,
                                         final Double durationInMs);

  protected abstract void changeDuration(final ProcessInstanceEngineDto processInstanceDto, final Double durationInMs);

  protected abstract UserTaskDurationTime getUserTaskDurationTime();

  protected abstract ProcessGroupByType getGroupByType();

  protected abstract ProcessReportDataType getReportDataType();

  protected abstract void changeUserTaskDates(final Map<String, OffsetDateTime> updates);

  protected abstract void changeUserTaskDate(final ProcessInstanceEngineDto processInstance,
                                             final String userTaskKey,
                                             final OffsetDateTime dateToChangeTo);
}
