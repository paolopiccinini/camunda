pipelineJob('sonarqube_analysis') {

  displayName 'Sonarqube Analysis'
  description 'Run sonarqube analysis once nightly.'

  // By default, this job is disabled in non-prod envs.
  if (binding.variables.get("ENVIRONMENT") != "prod") {
    disabled()
  }

  definition {
    cps {
      script(readFileFromWorkspace('.ci/pipelines/sonarqube_analysis.groovy'))
      sandbox()
    }
  }

  parameters {
    stringParam('BRANCH', binding.variables.get('GIT_LOCAL_BRANCH', 'master'), 'Branch to use for Sonarqube analysis.')
    stringParam('ES_VERSION', '', 'Elasticsearch version to use, defaults to reading it from pom.xml.')
    stringParam('CAMBPM_VERSION', '', 'Camunda BPM version to use, defaults to reading it from pom.xml.')
  }

  properties {
    pipelineTriggers {
      triggers {
        cron {
          spec('H 0 * * *')
        }
      }
    }
  }
}
