/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.identity;

import io.camunda.zeebe.engine.processing.distribution.CommandDistributionBehavior;
import io.camunda.zeebe.engine.processing.streamprocessor.DistributedTypedRecordProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedRejectionWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.state.distribution.DistributionQueue;
import io.camunda.zeebe.engine.state.mutable.MutableProcessingState;
import io.camunda.zeebe.protocol.impl.record.value.authorization.AuthorizationRecord;
import io.camunda.zeebe.protocol.record.intent.AuthorizationIntent;
import io.camunda.zeebe.protocol.record.value.PermissionType;
import io.camunda.zeebe.stream.api.records.TypedRecord;
import io.camunda.zeebe.stream.api.state.KeyGenerator;

public class AuthorizationDeleteProcessor
    implements DistributedTypedRecordProcessor<AuthorizationRecord> {

  private final KeyGenerator keyGenerator;
  private final CommandDistributionBehavior distributionBehavior;
  private final StateWriter stateWriter;
  private final TypedResponseWriter responseWriter;
  private final TypedRejectionWriter rejectionWriter;
  private final PermissionsBehavior permissionsBehavior;

  public AuthorizationDeleteProcessor(
      final Writers writers,
      final KeyGenerator keyGenerator,
      final MutableProcessingState processingState,
      final CommandDistributionBehavior distributionBehavior,
      final AuthorizationCheckBehavior authCheckBehavior) {
    this.keyGenerator = keyGenerator;
    this.distributionBehavior = distributionBehavior;
    stateWriter = writers.state();
    responseWriter = writers.response();
    rejectionWriter = writers.rejection();
    permissionsBehavior = new PermissionsBehavior(processingState, authCheckBehavior);
  }

  @Override
  public void processNewCommand(final TypedRecord<AuthorizationRecord> command) {
    permissionsBehavior
        .isAuthorized(command, PermissionType.DELETE)
        .flatMap(permissionsBehavior::authorizationExist)
        .ifRightOrLeft(
            authorizationRecord -> writeEventAndDistribute(command, authorizationRecord),
            (rejection) -> {
              rejectionWriter.appendRejection(command, rejection.type(), rejection.reason());
              responseWriter.writeRejectionOnCommand(command, rejection.type(), rejection.reason());
            });
  }

  @Override
  public void processDistributedCommand(final TypedRecord<AuthorizationRecord> command) {
    permissionsBehavior
        .permissionDoesNotExist(command.getValue())
        .ifRightOrLeft(
            ignored ->
                stateWriter.appendFollowUpEvent(
                    command.getKey(), AuthorizationIntent.DELETED, command.getValue()),
            rejection ->
                rejectionWriter.appendRejection(command, rejection.type(), rejection.reason()));

    distributionBehavior.acknowledgeCommand(command);
  }

  private void writeEventAndDistribute(
      final TypedRecord<AuthorizationRecord> command, final long authorizationKey) {
    final long key = keyGenerator.nextKey();
    final var record = new AuthorizationRecord().setAuthorizationKey(authorizationKey);
    stateWriter.appendFollowUpEvent(key, AuthorizationIntent.DELETED, record);
    distributionBehavior
        .withKey(key)
        .inQueue(DistributionQueue.IDENTITY.getQueueId())
        .distribute(command);
    responseWriter.writeEventOnCommand(key, AuthorizationIntent.DELETED, record, command);
  }
}
