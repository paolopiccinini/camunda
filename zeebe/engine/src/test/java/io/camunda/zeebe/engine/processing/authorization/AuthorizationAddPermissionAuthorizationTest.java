/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.security.configuration.ConfiguredUser;
import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.protocol.record.Assertions;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.intent.AuthorizationIntent;
import io.camunda.zeebe.protocol.record.intent.UserIntent;
import io.camunda.zeebe.protocol.record.value.AuthorizationResourceType;
import io.camunda.zeebe.protocol.record.value.PermissionType;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.util.List;
import java.util.UUID;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;

public class AuthorizationAddPermissionAuthorizationTest {

  private static final ConfiguredUser DEFAULT_USER =
      new ConfiguredUser(
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString(),
          UUID.randomUUID().toString());

  @ClassRule
  public static final EngineRule ENGINE =
      EngineRule.singlePartition()
          .withoutAwaitingIdentitySetup()
          .withSecurityConfig(cfg -> cfg.getAuthorizations().setEnabled(true))
          .withSecurityConfig(cfg -> cfg.getInitialization().setUsers(List.of(DEFAULT_USER)));

  private static long defaultUserKey = -1L;
  @Rule public final TestWatcher recordingExporterTestWatcher = new RecordingExporterTestWatcher();

  @BeforeClass
  public static void beforeAll() {
    defaultUserKey =
        RecordingExporter.userRecords(UserIntent.CREATED)
            .withUsername(DEFAULT_USER.getUsername())
            .getFirst()
            .getKey();
  }

  @Test
  public void shouldBeAuthorizedToAddPermissionsWithDefaultUser() {
    // given
    final var userKey = createUser();

    // when
    ENGINE
        .authorization()
        .permission()
        .withOwnerKey(userKey)
        .withResourceType(AuthorizationResourceType.DEPLOYMENT)
        .withPermission(PermissionType.DELETE, "*")
        .add(defaultUserKey);

    // then
    assertThat(
            RecordingExporter.authorizationRecords(AuthorizationIntent.PERMISSION_ADDED)
                .withOwnerKey(userKey)
                .exists())
        .isTrue();
  }

  @Test
  public void shouldBeAuthorizedToAddPermissionsWithUser() {
    // given
    final var userKey = createUser();
    addPermissionToUser(userKey, AuthorizationResourceType.AUTHORIZATION, PermissionType.UPDATE);

    // when
    ENGINE
        .authorization()
        .permission()
        .withOwnerKey(userKey)
        .withResourceType(AuthorizationResourceType.DEPLOYMENT)
        .withPermission(PermissionType.DELETE, "*")
        .add(userKey);

    // then
    assertThat(
            RecordingExporter.authorizationRecords(AuthorizationIntent.PERMISSION_ADDED)
                .withOwnerKey(userKey)
                .exists())
        .isTrue();
  }

  @Test
  public void shouldBeForbiddenToAddPermissionsIfNoPermissions() {
    // given
    final var userKey = createUser();

    // when
    final var rejection =
        ENGINE
            .authorization()
            .permission()
            .withOwnerKey(userKey)
            .withResourceType(AuthorizationResourceType.DEPLOYMENT)
            .withPermission(PermissionType.DELETE, "*")
            .expectRejection()
            .add(userKey);

    // then
    Assertions.assertThat(rejection)
        .hasRejectionType(RejectionType.FORBIDDEN)
        .hasRejectionReason(
            "Insufficient permissions to perform operation 'UPDATE' on resource 'AUTHORIZATION'");
  }

  private static long createUser() {
    return ENGINE
        .user()
        .newUser(UUID.randomUUID().toString())
        .withPassword(UUID.randomUUID().toString())
        .withName(UUID.randomUUID().toString())
        .withEmail(UUID.randomUUID().toString())
        .create()
        .getKey();
  }

  private void addPermissionToUser(
      final long userKey,
      final AuthorizationResourceType authorization,
      final PermissionType permissionType) {
    ENGINE
        .authorization()
        .permission()
        .withOwnerKey(userKey)
        .withResourceType(authorization)
        .withPermission(permissionType, "*")
        .add(defaultUserKey);
  }
}
