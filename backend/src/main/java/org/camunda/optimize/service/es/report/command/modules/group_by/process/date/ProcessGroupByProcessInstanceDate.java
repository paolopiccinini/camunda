/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.report.command.modules.group_by.process.date;

import org.camunda.optimize.dto.optimize.query.report.single.group.GroupByDateUnit;
import org.camunda.optimize.dto.optimize.query.report.single.process.ProcessReportDataDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.group.ProcessGroupByDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.group.value.DateGroupByValueDto;
import org.camunda.optimize.dto.optimize.query.sorting.SortOrder;
import org.camunda.optimize.dto.optimize.query.sorting.ReportSortingDto;
import org.camunda.optimize.service.es.OptimizeElasticsearchClient;
import org.camunda.optimize.service.es.filter.ProcessQueryFilterEnhancer;
import org.camunda.optimize.service.es.report.MinMaxStatDto;
import org.camunda.optimize.service.es.report.command.exec.ExecutionContext;
import org.camunda.optimize.service.es.report.command.modules.group_by.GroupByPart;
import org.camunda.optimize.service.es.report.command.modules.result.CompositeCommandResult;
import org.camunda.optimize.service.es.report.command.modules.result.CompositeCommandResult.GroupByResult;
import org.camunda.optimize.service.es.report.command.process.util.ProcessInstanceQueryUtil;
import org.camunda.optimize.service.es.report.command.util.DateAggregationService;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.camunda.optimize.service.es.report.command.util.FilterLimitedAggregationUtil.isResultComplete;
import static org.camunda.optimize.service.es.report.command.util.FilterLimitedAggregationUtil.unwrapFilterLimitedAggregations;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.PROCESS_INSTANCE_INDEX_NAME;
import static org.elasticsearch.index.query.QueryBuilders.existsQuery;

public abstract class ProcessGroupByProcessInstanceDate extends GroupByPart<ProcessReportDataDto> {

  protected final ConfigurationService configurationService;
  protected final DateAggregationService dateAggregationService;
  protected final OptimizeElasticsearchClient esClient;
  protected final ProcessQueryFilterEnhancer queryFilterEnhancer;


  protected ProcessGroupByProcessInstanceDate(final ConfigurationService configurationService,
                                              final DateAggregationService dateAggregationService,
                                              final OptimizeElasticsearchClient esClient,
                                              final ProcessQueryFilterEnhancer queryFilterEnhancer) {
    this.configurationService = configurationService;
    this.dateAggregationService = dateAggregationService;
    this.esClient = esClient;
    this.queryFilterEnhancer = queryFilterEnhancer;
  }

  @Override
  public Optional<MinMaxStatDto> calculateDateRangeForAutomaticGroupByDate(final ExecutionContext<ProcessReportDataDto> context,
                                                                           final BoolQueryBuilder baseQuery) {
    if (context.getReportData().getGroupBy().getValue() instanceof DateGroupByValueDto) {
      DateGroupByValueDto groupByDate = (DateGroupByValueDto) context.getReportData().getGroupBy().getValue();
      if (GroupByDateUnit.AUTOMATIC.equals(groupByDate.getUnit())) {
        return Optional.of(
          dateAggregationService.getCrossFieldMinMaxStats(
            baseQuery,
            PROCESS_INSTANCE_INDEX_NAME,
            getDateField()
          ));
      }
    }
    return Optional.empty();
  }

  @Override
  public void adjustSearchRequest(final SearchRequest searchRequest,
                                  final BoolQueryBuilder baseQuery,
                                  final ExecutionContext<ProcessReportDataDto> context) {
    super.adjustSearchRequest(searchRequest, baseQuery, context);
    baseQuery.must(existsQuery(getDateField()));
  }

  protected abstract ProcessGroupByDto<DateGroupByValueDto> getGroupByType();

  public abstract String getDateField();

  @Override
  public List<AggregationBuilder> createAggregation(final SearchSourceBuilder searchSourceBuilder,
                                                    final ExecutionContext<ProcessReportDataDto> context) {
    final GroupByDateUnit unit = getGroupByDateUnit(context.getReportData());
    return createAggregation(searchSourceBuilder, context, unit);
  }

  public List<AggregationBuilder> createAggregation(final SearchSourceBuilder searchSourceBuilder,
                                                    final ExecutionContext<ProcessReportDataDto> context,
                                                    final GroupByDateUnit unit) {
    MinMaxStatDto stats = dateAggregationService.getMinMaxDateRange(
      context,
      searchSourceBuilder.query(),
      PROCESS_INSTANCE_INDEX_NAME,
      getDateField()
    );
    if (stats.isEmpty()) {
      return Collections.emptyList();
    }

    if (GroupByDateUnit.AUTOMATIC.equals(unit)) {
      return createAutomaticIntervalAggregation(searchSourceBuilder, context, stats);
    }

    return Collections.singletonList(
      dateAggregationService.createFilterLimitedProcessDateHistogramWithSubAggregation(
        unit,
        getDateField(),
        context.getTimezone(),
        getGroupByType().getType(),
        context.getReportData().getFilter(),
        ProcessInstanceQueryUtil.getLatestDate(searchSourceBuilder.query(), getDateField(), esClient).orElse(null),
        queryFilterEnhancer,
        distributedByPart.createAggregation(context)
      )
    );
  }

  protected List<AggregationBuilder> createAutomaticIntervalAggregation(final SearchSourceBuilder builder,
                                                                        final ExecutionContext<ProcessReportDataDto> context,
                                                                        final MinMaxStatDto stats) {
    Optional<AggregationBuilder> automaticIntervalAggregation =
      dateAggregationService.createAutomaticIntervalAggregation(
        stats,
        getDateField(),
        context.getTimezone()
      );

    return automaticIntervalAggregation.map(agg -> agg.subAggregation(distributedByPart.createAggregation(context)))
      .map(Collections::singletonList)
      .orElseGet(() -> createAggregation(builder, context, GroupByDateUnit.MONTH));
  }

  @Override
  public void addQueryResult(final CompositeCommandResult result,
                             final SearchResponse response,
                             final ExecutionContext<ProcessReportDataDto> context) {
    result.setGroups(processAggregations(response, response.getAggregations(), context));
    result.setIsComplete(isResultComplete(response));
    result.setSorting(
      context.getReportConfiguration()
        .getSorting()
        .orElseGet(() -> new ReportSortingDto(ReportSortingDto.SORT_BY_KEY, SortOrder.ASC))
    );
  }

  private List<GroupByResult> processAggregations(final SearchResponse response,
                                                  final Aggregations aggregations,
                                                  final ExecutionContext<ProcessReportDataDto> context) {
    if (aggregations == null) {
      // aggregations are null when there are no instances in the report
      return Collections.emptyList();
    }

    final Optional<Aggregations> unwrappedLimitedAggregations = unwrapFilterLimitedAggregations(aggregations);
    Map<String, Aggregations> keyToAggregationMap;
    if (unwrappedLimitedAggregations.isPresent()) {
      keyToAggregationMap = dateAggregationService.mapHistogramAggregationsToKeyAggregationMap(
        unwrappedLimitedAggregations.get(),
        context.getTimezone()
      );
    } else {
      keyToAggregationMap = dateAggregationService.mapRangeAggregationsToKeyAggregationMap(
        aggregations,
        context.getTimezone()
      );
    }
    return mapKeyToAggMapToGroupByResults(keyToAggregationMap, response, context);
  }

  @Override
  protected void addGroupByAdjustmentsForCommandKeyGeneration(final ProcessReportDataDto reportData) {
    reportData.setGroupBy(getGroupByType());
  }

  private List<GroupByResult> mapKeyToAggMapToGroupByResults(final Map<String, Aggregations> keyToAggregationMap,
                                                             final SearchResponse response,
                                                             final ExecutionContext<ProcessReportDataDto> context) {
    return keyToAggregationMap
      .entrySet()
      .stream()
      .map(stringBucketEntry -> GroupByResult.createGroupByResult(
        stringBucketEntry.getKey(),
        distributedByPart.retrieveResult(response, stringBucketEntry.getValue(), context)
      ))
      .collect(Collectors.toList());
  }

  private GroupByDateUnit getGroupByDateUnit(final ProcessReportDataDto processReportData) {
    return ((DateGroupByValueDto) processReportData.getGroupBy().getValue()).getUnit();
  }

}
