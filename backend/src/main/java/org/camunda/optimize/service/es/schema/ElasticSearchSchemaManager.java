package org.camunda.optimize.service.es.schema;

import org.camunda.optimize.service.util.ConfigurationService;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

@Component
public class ElasticSearchSchemaManager {

  private Logger logger = LoggerFactory.getLogger(ElasticSearchSchemaManager.class);

  @Autowired
  private TransportClient esclient;

  @Autowired
  private ConfigurationService configurationService;

  private List<TypeMappingCreator> mappings = new LinkedList<>();

  public void addMapping(TypeMappingCreator mapping) {
    mappings.add(mapping);
  }

  public boolean schemaAlreadyExists() {
    IndicesExistsResponse response = esclient
      .admin()
      .indices()
      .prepareExists(configurationService.getOptimizeIndex())
      .get();
    return response.isExists();
  }

  public void updateMappings() {
    for (TypeMappingCreator mapping : mappings) {
      createSingleSchema(mapping.getType(), mapping.getSource());
    }
  }

  public void createOptimizeIndex() {
    Settings indexSettings = null;
    try {
      indexSettings = buildSettings();
    } catch (IOException e) {
      logger.error("Could not create settings!", e);
    }
    esclient
      .admin()
        .indices()
          .prepareCreate(configurationService.getOptimizeIndex())
          .setSettings(indexSettings)
      .get();
    esclient.admin().indices().prepareRefresh().get();
  }

  private Settings buildSettings() throws IOException {
    return Settings.builder().loadFromSource(jsonBuilder()
      .startObject()
        .startObject("analysis")
          .startObject("analyzer")
            .startObject(configurationService.getAnalyzerName())
              .field("type", "custom")
              .field("tokenizer", configurationService.getTokenizer())
              .field("filter", new String[]{configurationService.getTokenFilter()})
            .endObject()
        . endObject()
        .endObject()
      .endObject().string()).build();
  }

  private void createSingleSchema(String type, String content) {
    esclient
      .admin()
      .indices()
      .preparePutMapping(configurationService.getOptimizeIndex())
      .setType(type)
      .setSource(content)
      .get();
  }

}
