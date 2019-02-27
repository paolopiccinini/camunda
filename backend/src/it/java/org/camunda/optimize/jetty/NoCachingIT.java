package org.camunda.optimize.jetty;

import org.camunda.optimize.service.license.LicenseManager;
import org.camunda.optimize.test.it.rule.ElasticSearchIntegrationTestRule;
import org.camunda.optimize.test.it.rule.EmbeddedOptimizeRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.camunda.optimize.jetty.NoCachingFilter.NO_STORE;
import static org.camunda.optimize.jetty.OptimizeResourceConstants.NO_CACHE_RESOURCES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;


public class NoCachingIT {

  public ElasticSearchIntegrationTestRule elasticSearchRule = new ElasticSearchIntegrationTestRule();
  public EmbeddedOptimizeRule embeddedOptimizeRule = new EmbeddedOptimizeRule();

  @Rule
  public RuleChain chain = RuleChain
    .outerRule(elasticSearchRule).around(embeddedOptimizeRule);

  private LicenseManager licenseManager;

  @Before
  public void setup() throws IOException, URISyntaxException {
    licenseManager = embeddedOptimizeRule.getApplicationContext().getBean(LicenseManager.class);
    addLicenseToOptimize();
  }

  @After
  public void resetBasePackage() {
    licenseManager.resetLicenseFromFile();
  }

  @Test
  public void loadingOfStaticResourcesContainsNoCacheHeader() {
    // given
    for (String staticResource : NO_CACHE_RESOURCES) {

      // when
      Response response =
        embeddedOptimizeRule.rootTarget(staticResource).request().get();

      // then
      assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL), is(NO_STORE));
    }
  }

  @Test
  public void restApiCallResponseContainsNoCacheHeader() {
    // when
    Response response =
      embeddedOptimizeRule.getRequestExecutor().buildGetOptimizeVersionRequest().execute();

    // then
    assertThat(response.getHeaderString(HttpHeaders.CACHE_CONTROL), is(NO_STORE));
  }

  private String readFileToString(String filePath) throws IOException, URISyntaxException {
    return new String(Files.readAllBytes(Paths.get(getClass().getResource(filePath).toURI())), StandardCharsets.UTF_8);
  }

  private void addLicenseToOptimize() throws IOException, URISyntaxException {
    String license = readFileToString("/license/ValidTestLicense.txt");

    Response response =
      embeddedOptimizeRule.getRequestExecutor()
        .buildValidateAndStoreLicenseRequest(license)
        .execute();
    assertThat(response.getStatus(), is(200));
  }
}
