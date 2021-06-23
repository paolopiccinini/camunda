/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package io.camunda.tasklist.webapp.security;

import static io.camunda.tasklist.schema.indices.TasklistWebSessionIndex.ATTRIBUTES;
import static io.camunda.tasklist.schema.indices.TasklistWebSessionIndex.CREATION_TIME;
import static io.camunda.tasklist.schema.indices.TasklistWebSessionIndex.ID;
import static io.camunda.tasklist.schema.indices.TasklistWebSessionIndex.LAST_ACCESSED_TIME;
import static io.camunda.tasklist.schema.indices.TasklistWebSessionIndex.MAX_INACTIVE_INTERVAL_IN_SECONDS;

import io.camunda.tasklist.es.RetryElasticsearchClient;
import io.camunda.tasklist.property.TasklistProperties;
import io.camunda.tasklist.schema.indices.TasklistWebSessionIndex;
import io.camunda.tasklist.util.SoftHashMap;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.elasticsearch.action.search.SearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.session.ExpiringSession;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.stereotype.Component;

@Configuration
@ConditionalOnProperty(
    prefix = TasklistProperties.PREFIX,
    value = "persistentSessionsEnabled",
    havingValue = "true")
@Component
@EnableSpringHttpSession
public class ElasticsearchSessionRepository
    implements SessionRepository<ElasticsearchSessionRepository.ElasticsearchSession> {

  public static final int DELETE_EXPIRED_SESSIONS_DELAY = 1_000 * 60 * 30; // min

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ElasticsearchSessionRepository.class);

  @Autowired private RetryElasticsearchClient retryElasticsearchClient;

  @Autowired private GenericConversionService conversionService;

  @Autowired private TasklistWebSessionIndex operateWebSessionIndex;

  @Autowired
  @Qualifier("sessionThreadPoolScheduler")
  private ThreadPoolTaskScheduler sessionThreadScheduler;

  private Map<String, ElasticsearchSession> cache = new SoftHashMap<>();

  @PostConstruct
  private void setUp() {
    LOGGER.debug("Persistent sessions in Elasticsearch enabled");
    setupConverter();
    startExpiredSessionCheck();
  }

  @PreDestroy
  private void tearDown() {
    LOGGER.debug("Shutdown ElasticsearchSessionRepository");
  }

  @Bean("sessionThreadPoolScheduler")
  public ThreadPoolTaskScheduler getTaskScheduler() {
    final ThreadPoolTaskScheduler executor = new ThreadPoolTaskScheduler();
    executor.setPoolSize(5);
    executor.setThreadNamePrefix("operate_session_");
    executor.initialize();
    return executor;
  }

  @Bean
  public CookieSerializer cookieSerializer() {
    final DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(TasklistURIs.COOKIE_JSESSIONID);
    return serializer;
  }

  private void setupConverter() {
    conversionService.addConverter(Object.class, byte[].class, new SerializingConverter());
    conversionService.addConverter(byte[].class, Object.class, new DeserializingConverter());
  }

  private void startExpiredSessionCheck() {
    sessionThreadScheduler.scheduleAtFixedRate(
        this::removedExpiredSessions, DELETE_EXPIRED_SESSIONS_DELAY);
  }

  private void removedExpiredSessions() {
    LOGGER.debug("Check for expired sessions");
    final SearchRequest searchRequest =
        new SearchRequest(operateWebSessionIndex.getFullQualifiedName());
    retryElasticsearchClient.doWithEachSearchResult(
        searchRequest,
        sh -> {
          final ElasticsearchSession session = documentToSession(sh.getSourceAsMap());
          LOGGER.debug("Check if session {} is expired: {}", session, session.isExpired());
          if (session.isExpired()) {
            delete(session.getId());
          }
        });
  }

  @Override
  public ElasticsearchSession createSession() {
    // Frontend e2e tests are relying on this pattern
    final String sessionId = UUID.randomUUID().toString().replace("-", "");

    final ElasticsearchSession session = new ElasticsearchSession(sessionId);
    LOGGER.debug(
        "Create session {} with maxInactiveTime {} s",
        session,
        session.getMaxInactiveIntervalInSeconds());
    return session;
  }

  @Override
  public void save(ElasticsearchSession session) {
    LOGGER.debug("Save session {}", session);
    if (session.isExpired()) {
      delete(session.getId());
      return;
    }
    cache.put(session.getId(), session);
    if (session.isChanged()) {
      LOGGER.debug("Session {} changed, save in Elasticsearch.", session);
      executeAsyncElasticsearchRequest(
          () ->
              retryElasticsearchClient.createOrUpdateDocument(
                  operateWebSessionIndex.getFullQualifiedName(),
                  session.getId(),
                  sessionToDocument(session)));
      session.clearChangeFlag();
    }
  }

  @Override
  public ElasticsearchSession getSession(final String id) {
    LOGGER.debug("Retrieve session {} from Elasticsearch", id);
    ElasticsearchSession session = null;
    retryElasticsearchClient.refresh(operateWebSessionIndex.getFullQualifiedName());
    final Map<String, Object> document =
        retryElasticsearchClient.getDocument(operateWebSessionIndex.getFullQualifiedName(), id);
    if (document != null) {
      session = documentToSession(document);
    }
    if (session != null && session.isExpired()) {
      delete(session.getId());
      return null;
    }
    return session;
  }

  @Override
  public void delete(String id) {
    LOGGER.debug("Delete session {}", id);
    cache.remove(id);
    executeAsyncElasticsearchRequest(
        () ->
            retryElasticsearchClient.deleteDocument(
                operateWebSessionIndex.getFullQualifiedName(), id));
  }

  private byte[] serialize(Object object) {
    return (byte[])
        conversionService.convert(
            object, TypeDescriptor.valueOf(Object.class), TypeDescriptor.valueOf(byte[].class));
  }

  private Object deserialize(byte[] bytes) {
    return conversionService.convert(
        bytes, TypeDescriptor.valueOf(byte[].class), TypeDescriptor.valueOf(Object.class));
  }

  private Map<String, Object> sessionToDocument(ElasticsearchSession session) {
    final Map<String, byte[]> attributes = new HashMap<>();
    session
        .getAttributeNames()
        .forEach(name -> attributes.put(name, serialize(session.getAttribute(name))));
    return Map.of(
        ID,
        session.getId(),
        CREATION_TIME,
        session.getCreationTime(),
        LAST_ACCESSED_TIME,
        session.getLastAccessedTime(),
        MAX_INACTIVE_INTERVAL_IN_SECONDS,
        session.getMaxInactiveIntervalInSeconds(),
        ATTRIBUTES,
        attributes);
  }

  private ElasticsearchSession documentToSession(Map<String, Object> document) {
    final String sessionId = (String) document.get(ID);
    final ElasticsearchSession session = new ElasticsearchSession(sessionId);
    session.setCreationTime((long) document.get(CREATION_TIME));
    session.setLastAccessedTime((long) document.get(LAST_ACCESSED_TIME));
    session.setMaxInactiveIntervalInSeconds((int) document.get(MAX_INACTIVE_INTERVAL_IN_SECONDS));

    final Object attributesObject = document.get(ATTRIBUTES);
    if (attributesObject != null
        && attributesObject.getClass().isInstance(new HashMap<String, String>())) {
      final Map<String, String> attributes = (Map<String, String>) document.get(ATTRIBUTES);
      attributes
          .keySet()
          .forEach(
              name ->
                  session.setAttribute(
                      name, deserialize(Base64.getDecoder().decode(attributes.get(name)))));
    }
    return session;
  }

  private void executeAsyncElasticsearchRequest(Runnable requestRunnable) {
    sessionThreadScheduler.submit(requestRunnable);
  }

  static class ElasticsearchSession implements ExpiringSession {

    private final MapSession delegate;

    private boolean changed;

    public ElasticsearchSession(String id) {
      delegate = new MapSession(id);
    }

    boolean isChanged() {
      return changed;
    }

    void clearChangeFlag() {
      changed = false;
    }

    public String getId() {
      return delegate.getId();
    }

    public ElasticsearchSession setId(String id) {
      delegate.setId(id);
      return this;
    }

    public <T> T getAttribute(String attributeName) {
      return delegate.getAttribute(attributeName);
    }

    public Set<String> getAttributeNames() {
      return delegate.getAttributeNames();
    }

    public void setAttribute(String attributeName, Object attributeValue) {
      delegate.setAttribute(attributeName, attributeValue);
      changed = true;
    }

    public void removeAttribute(String attributeName) {
      delegate.removeAttribute(attributeName);
      changed = true;
    }

    public long getCreationTime() {
      return delegate.getCreationTime();
    }

    public void setCreationTime(long creationTime) {
      delegate.setCreationTime(creationTime);
      changed = true;
    }

    public void setLastAccessedTime(long lastAccessedTime) {
      delegate.setLastAccessedTime(lastAccessedTime);
      changed = true;
    }

    public long getLastAccessedTime() {
      return delegate.getLastAccessedTime();
    }

    public void setMaxInactiveIntervalInSeconds(int interval) {
      delegate.setMaxInactiveIntervalInSeconds(interval);
      changed = true;
    }

    public int getMaxInactiveIntervalInSeconds() {
      return delegate.getMaxInactiveIntervalInSeconds();
    }

    public boolean isExpired() {
      return delegate.isExpired();
    }

    @Override
    public String toString() {
      return String.format("ElasticsearchSession: %s ", getId());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final ElasticsearchSession session = (ElasticsearchSession) o;
      return Objects.equals(getId(), session.getId());
    }

    @Override
    public int hashCode() {
      return Objects.hash(getId());
    }
  }
}
