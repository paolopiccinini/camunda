package org.camunda.optimize.test.rule;

import org.camunda.optimize.dto.engine.CountDto;
import org.camunda.optimize.dto.optimize.CredentialsDto;
import org.camunda.optimize.dto.optimize.ProgressDto;
import org.camunda.optimize.service.importing.ImportJobExecutor;
import org.camunda.optimize.service.importing.ImportScheduleJob;
import org.camunda.optimize.service.importing.ImportService;
import org.camunda.optimize.service.importing.ImportServiceProvider;
import org.camunda.optimize.test.util.PropertyUtil;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

/**
 * Helper rule to start embedded jetty with Camunda Optimize on bord.
 *
 * @author Askar Akhmerov
 */
public class EmbeddedOptimizeRule extends TestWatcher {

  private static final String DEFAULT_CONTEXT_LOCATION = "classpath:embeddedOptimizeContext.xml";
  private final String contextLocation;
  private Logger logger = LoggerFactory.getLogger(EmbeddedOptimizeRule.class);

  private TestEmbeddedCamundaOptimize camundaOptimize;
  private Properties properties;
  private String propertiesLocation = "it/it-test.properties";

  public EmbeddedOptimizeRule(String contextLocation) {
    this.contextLocation = contextLocation;
  }

  public EmbeddedOptimizeRule() {
    this(DEFAULT_CONTEXT_LOCATION);
  }

  public void init() {

    properties = PropertyUtil.loadProperties(propertiesLocation);
  }

  public void importEngineEntities() {
    getJobExecutor().startExecutingImportJobs();
    for (ImportService importService : getServiceProvider().getServices()) {
      ImportScheduleJob job = new ImportScheduleJob();
      job.setImportService(importService);
      job.execute();
    }
    getJobExecutor().stopExecutingImportJobs();
  }

  private ImportServiceProvider getServiceProvider() {
    return camundaOptimize.getImportServiceProvider();
  }

  private ImportJobExecutor getJobExecutor() {
    return camundaOptimize.getImportJobExecutor();
  }

  protected void starting(Description description) {
    startOptimize();
  }

  public void startOptimize() {
    if (camundaOptimize == null) {
      camundaOptimize = new TestEmbeddedCamundaOptimize(contextLocation);
      init();
    }
    try {
      camundaOptimize.start();
    } catch (Exception e) {
      logger.error("Failed to start Optimize", e);
    }
  }

  protected void finished(Description description) {
    stopOptimize();
  }

  public void stopOptimize() {
    try {
      camundaOptimize.destroy();
      camundaOptimize = null;
    } catch (Exception e) {
      logger.error("Failed to stop Optimize", e);
    }
  }

  public String authenticateAdmin() {
    Response tokenResponse = authenticateAdminRequest();

    return tokenResponse.readEntity(String.class);
  }

  public Response authenticateAdminRequest() {
    CredentialsDto entity = new CredentialsDto();
    entity.setUsername("admin");
    entity.setPassword("admin");

    return target("authentication")
        .request()
        .post(Entity.json(entity));
  }

  public final WebTarget target(String path) {
    return this.target().path(path);
  }

  public final WebTarget target() {
    return this.client().target(getBaseUri());
  }

  private String getBaseUri() {
    return properties.getProperty("camunda.optimize.test.embedded-optimize");
  }

  public final Client client() {
    return this.getClient();
  }

  private Client getClient() {
    Client client = ClientBuilder.newClient();
    client.property(ClientProperties.CONNECT_TIMEOUT, 10000);
    client.property(ClientProperties.READ_TIMEOUT,    10000);
    return client;
  }

  public String getProcessDefinitionEndpoint() {
    return properties.getProperty("camunda.optimize.test.embedded-optimize.process-definition");
  }

  public List<Integer> getImportIndexes() {
    List<Integer> indexes = new LinkedList<>();
    for (ImportService importService : getServiceProvider().getServices()) {
      indexes.add(importService.getImportStartIndex());
    }
    return indexes;
  }

  public void resetImportStartIndexes() {
    getJobExecutor().startExecutingImportJobs();
    for (ImportService importService : getServiceProvider().getServices()) {
      importService.resetImportStartIndex();
    }
    getJobExecutor().stopExecutingImportJobs();
  }

  public void initializeSchema() {
    camundaOptimize.initializeIndex();
  }

  public int getProgressValue() {
    return this.target()
        .path("status/import-progress")
        .request()
        .get(ProgressDto.class).getProgress();
  }

  public void startImportScheduler() {
    camundaOptimize.startImportScheduler();
  }

  public boolean isImporting() {
    return this.getJobExecutor().isActive();
  }

  public ApplicationContext getApplicationContext() {
    return camundaOptimize.getApplicationContext();
  }


  public int getMaxVariableValueListSize() {
    return Integer.parseInt(properties.getProperty("camunda.optimize.variable.max.valueList.size"));
  }
}
