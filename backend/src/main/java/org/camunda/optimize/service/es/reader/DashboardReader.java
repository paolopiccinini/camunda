/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.search.join.ScoreMode;
import org.camunda.optimize.dto.optimize.query.dashboard.DashboardDefinitionDto;
import org.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.util.List;

import static org.camunda.optimize.service.es.schema.OptimizeIndexNameHelper.getOptimizeIndexAliasForType;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.DASHBOARD_TYPE;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.LIST_FETCH_LIMIT;

@Component
public class DashboardReader {
  private static final Logger logger = LoggerFactory.getLogger(DashboardReader.class);

  private RestHighLevelClient esClient;
  private ConfigurationService configurationService;
  private ObjectMapper objectMapper;

  @Autowired
  public DashboardReader(RestHighLevelClient esClient,
                         ConfigurationService configurationService,
                         ObjectMapper objectMapper) {
    this.esClient = esClient;
    this.configurationService = configurationService;
    this.objectMapper = objectMapper;
  }

  public DashboardDefinitionDto getDashboard(String dashboardId) {
    logger.debug("Fetching dashboard with id [{}]", dashboardId);
    GetRequest getRequest = new GetRequest(
      getOptimizeIndexAliasForType(DASHBOARD_TYPE),
      DASHBOARD_TYPE,
      dashboardId
    );

    GetResponse getResponse;
    try {
      getResponse = esClient.get(getRequest, RequestOptions.DEFAULT);
    } catch (IOException e) {
      String reason = String.format("Could not fetch dashboard with id [%s]", dashboardId);
      logger.error(reason, e);
      throw new OptimizeRuntimeException(reason, e);
    }

    if (getResponse.isExists()) {
      String responseAsString = getResponse.getSourceAsString();
      try {
        return objectMapper.readValue(responseAsString, DashboardDefinitionDto.class);
      } catch (IOException e) {
        String reason = "Could not deserialize dashboard information for dashboard " + dashboardId;
        logger.error("Was not able to retrieve dashboard with id [{}] from Elasticsearch. Reason: reason");
        throw new OptimizeRuntimeException(reason, e);
      }
    } else {
      logger.error("Was not able to retrieve dashboard with id [{}] from Elasticsearch.", dashboardId);
      throw new NotFoundException("Dashboard does not exist! Tried to retried dashboard with id " + dashboardId);
    }
  }

  public List<DashboardDefinitionDto> findFirstDashboardsForReport(String reportId) {
    logger.debug("Fetching dashboards using report with id {}", reportId);

    final QueryBuilder getCombinedReportsBySimpleReportIdQuery = QueryBuilders.boolQuery()
      .filter(QueryBuilders.nestedQuery(
        "reports",
        QueryBuilders.termQuery("reports.id", reportId),
        ScoreMode.None
      ));

    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
    searchSourceBuilder.query(getCombinedReportsBySimpleReportIdQuery);
    searchSourceBuilder.size(LIST_FETCH_LIMIT);
    SearchRequest searchRequest =
      new SearchRequest(getOptimizeIndexAliasForType(DASHBOARD_TYPE))
        .types(DASHBOARD_TYPE)
        .source(searchSourceBuilder);

    SearchResponse searchResponse;
    try {
      searchResponse = esClient.search(searchRequest, RequestOptions.DEFAULT);
    } catch (IOException e) {
      String reason = String.format("Was not able to fetch dashboards for report with id [%s]", reportId);
      logger.error(reason, e);
      throw new OptimizeRuntimeException(reason, e);
    }

    return ElasticsearchHelper.mapHits(searchResponse.getHits(), DashboardDefinitionDto.class, objectMapper);
  }

  public List<DashboardDefinitionDto> getAllDashboards() {
    logger.debug("Fetching all available dashboards");
    SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
      .query(QueryBuilders.matchAllQuery())
      .size(LIST_FETCH_LIMIT);
    SearchRequest searchRequest =
      new SearchRequest(getOptimizeIndexAliasForType(DASHBOARD_TYPE))
        .types(DASHBOARD_TYPE)
        .source(searchSourceBuilder)
        .scroll(new TimeValue(configurationService.getElasticsearchScrollTimeout()));

    SearchResponse scrollResp;
    try {
      scrollResp = esClient.search(searchRequest, RequestOptions.DEFAULT);
    } catch (IOException e) {
      logger.error("Was not able to retrieve dashboards!", e);
      throw new OptimizeRuntimeException("Was not able to retrieve dashboards!", e);
    }

    return ElasticsearchHelper.retrieveAllScrollResults(
      scrollResp,
      DashboardDefinitionDto.class,
      objectMapper,
      esClient,
      configurationService.getElasticsearchScrollTimeout()
    );
  }

}
