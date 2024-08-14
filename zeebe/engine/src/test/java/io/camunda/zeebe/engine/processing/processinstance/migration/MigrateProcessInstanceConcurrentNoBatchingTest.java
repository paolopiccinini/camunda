/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.processinstance.migration;

import static io.camunda.zeebe.engine.processing.processinstance.migration.MigrationTestUtil.extractProcessDefinitionKeyByProcessId;
import static io.camunda.zeebe.protocol.record.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.camunda.zeebe.engine.state.mutable.MutableProcessingState;
import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.engine.util.RecordToWrite;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.impl.record.value.job.JobRecord;
import io.camunda.zeebe.protocol.impl.record.value.message.MessageRecord;
import io.camunda.zeebe.protocol.impl.record.value.message.ProcessMessageSubscriptionRecord;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceMigrationMappingInstruction;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceMigrationRecord;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.intent.MessageIntent;
import io.camunda.zeebe.protocol.record.intent.MessageSubscriptionIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceMigrationIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessMessageSubscriptionIntent;
import io.camunda.zeebe.protocol.record.intent.TimerIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.BpmnEventType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.protocol.record.value.ProcessMessageSubscriptionRecordValue;
import io.camunda.zeebe.protocol.record.value.TimerRecordValue;
import io.camunda.zeebe.test.util.BrokerClassRuleHelper;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class MigrateProcessInstanceConcurrentNoBatchingTest {

  @ClassRule
  public static final EngineRule ENGINE = EngineRule.singlePartition().maxCommandsInBatch(1);

  @Rule public final BrokerClassRuleHelper helper = new BrokerClassRuleHelper();

  @Test
  public void shouldContinueMigratedInstanceWithJobCompleteBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v1", s -> s.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v2", s -> s.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final long jobKey =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst()
            .getKey();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command().job(JobIntent.COMPLETE, new JobRecord()).key(jobKey),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var migrationRejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(migrationRejection)
        .hasRejectionType(RejectionType.INVALID_STATE)
        .hasRejectionReason(
            createMigrationRejectionDueConcurrentModificationReason(processInstanceKey));

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("B_v1")
        .await();

    ENGINE
        .processInstance()
        .withInstanceKey(processInstanceKey)
        .migration()
        .withTargetProcessDefinitionKey(targetProcessDefinitionKey)
        .addMappingInstruction("B_v1", "B_v2")
        .migrate();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldContinueMigratedInstanceWithJobCompleteAfter() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v1", s -> s.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v2", s -> s.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final long jobKey =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst()
            .getKey();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))),
        RecordToWrite.command().job(JobIntent.COMPLETE, new JobRecord()).key(jobKey));

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("A", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Ignore("Ignore until the migration is supported for tasks with boundary events")
  @Test
  public void shouldContinueMigratedInstanceWithTimerBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final Record<TimerRecordValue> timerCreated =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .timer(TimerIntent.TRIGGER, timerCreated.getValue())
            .key(timerCreated.getKey()),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var migrationRejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(migrationRejection)
        .hasRejectionType(RejectionType.INVALID_STATE)
        .hasRejectionReason(
            createMigrationRejectionDueConcurrentModificationReason(processInstanceKey));

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("B_v1")
        .await();

    ENGINE
        .processInstance()
        .withInstanceKey(processInstanceKey)
        .migration()
        .withTargetProcessDefinitionKey(targetProcessDefinitionKey)
        .addMappingInstruction("B_v1", "B_v2")
        .migrate();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Ignore("Ignore until the migration is supported for tasks with boundary events")
  @Test
  public void shouldContinueMigratedInstanceWithTimerAfter() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final Record<TimerRecordValue> timerCreated =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))),
        RecordToWrite.command()
            .timer(TimerIntent.TRIGGER, timerCreated.getValue())
            .key(timerCreated.getKey()));

    assertThat(
            RecordingExporter.timerRecords(TimerIntent.TRIGGER)
                .withProcessInstanceKey(processInstanceKey)
                .onlyCommandRejections()
                .findFirst())
        .describedAs(
            "Expect that the timer command is rejected because the migration recreate the subscription")
        .isPresent();

    ENGINE.increaseTime(Duration.ofHours(1));

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldContinueMigratedInstanceWithMessageBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    // the new process must use a different message name because of an inconsistent state exception
    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b -> b.message(m -> m.name("message").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b ->
                            b.message(m -> m.name("message2").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(processId)
            .withVariable("key", helper.getCorrelationValue())
            .create();

    RecordingExporter.messageSubscriptionRecords(MessageSubscriptionIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .await();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .message(
                MessageIntent.PUBLISH,
                new MessageRecord()
                    .setName("message")
                    .setCorrelationKey(helper.getCorrelationValue())
                    .setTimeToLive(Duration.ofHours(1).toMillis())),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var subscriptionRejection =
        RecordingExporter.processMessageSubscriptionRecords(
                ProcessMessageSubscriptionIntent.CORRELATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(subscriptionRejection)
        .describedAs(
            "Expect that the correlation is rejected because the subscription is already closing.")
        .hasRejectionType(RejectionType.INVALID_STATE);

    // The new process waits for a message with a different name. Continue the process instance by
    // publishing the message.
    ENGINE
        .message()
        .withName("message2")
        .withCorrelationKey(helper.getCorrelationValue())
        .publish();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldContinueMigratedInstanceWithMessageAfter() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    // the new process must use a different message name because of an inconsistent state exception
    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b -> b.message(m -> m.name("message").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b ->
                            b.message(m -> m.name("message2").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(processId)
            .withVariable("key", helper.getCorrelationValue())
            .create();

    RecordingExporter.messageSubscriptionRecords(MessageSubscriptionIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .await();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))),
        RecordToWrite.command()
            .message(
                MessageIntent.PUBLISH,
                new MessageRecord()
                    .setName("message")
                    .setCorrelationKey(helper.getCorrelationValue())
                    .setTimeToLive(Duration.ofHours(1).toMillis())));

    // The new process waits for a message with a different name. Continue the process instance by
    // publishing the message.
    ENGINE
        .message()
        .withName("message2")
        .withCorrelationKey(helper.getCorrelationValue())
        .publish();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldContinueMigratedInstanceWithMessageCorrelateBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    // the new process must use a different message name because of an inconsistent state exception
    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b -> b.message(m -> m.name("message").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent(
                        "message",
                        b ->
                            b.message(m -> m.name("message2").zeebeCorrelationKeyExpression("key")))
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey =
        ENGINE
            .processInstance()
            .ofBpmnProcessId(processId)
            .withVariable("key", helper.getCorrelationValue())
            .create();

    final Record<ProcessMessageSubscriptionRecordValue> subscriptionCreated =
        RecordingExporter.processMessageSubscriptionRecords(
                ProcessMessageSubscriptionIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    final var subscriptionCommand = new ProcessMessageSubscriptionRecord();
    subscriptionCommand.wrap((ProcessMessageSubscriptionRecord) subscriptionCreated.getValue());
    subscriptionCommand.setMessageKey(1);

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .processMessageSubscription(
                ProcessMessageSubscriptionIntent.CORRELATE, subscriptionCommand)
            .key(subscriptionCreated.getKey()),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var migrationRejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(migrationRejection)
        .hasRejectionType(RejectionType.INVALID_STATE)
        .hasRejectionReason(
            createMigrationRejectionDueConcurrentModificationReason(processInstanceKey));

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("B_v1")
        .await();

    ENGINE
        .processInstance()
        .withInstanceKey(processInstanceKey)
        .migration()
        .withTargetProcessDefinitionKey(targetProcessDefinitionKey)
        .addMappingInstruction("B_v1", "B_v2")
        .migrate();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Ignore("Ignore until the migration is supported for tasks with boundary events")
  @Test
  public void shouldContinueMigratedInstanceWithNonInterruptingTimerBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .cancelActivity(false)
                    .serviceTask("B_v1", t -> t.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .moveToActivity("A")
                    .endEvent("end_2_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", t -> t.zeebeJobType("A"))
                    .boundaryEvent("timer", b -> b.timerWithDuration("PT1H"))
                    .cancelActivity(false)
                    .serviceTask("B_v2", t -> t.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .moveToActivity("A")
                    .endEvent("end_2_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final Record<TimerRecordValue> timerCreated =
        RecordingExporter.timerRecords(TimerIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .timer(TimerIntent.TRIGGER, timerCreated.getValue())
            .key(timerCreated.getKey()),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var migrationRejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(migrationRejection)
        .hasRejectionType(RejectionType.INVALID_STATE)
        .hasRejectionReason(
            """
                Expected to migrate process instance '%d' \
                but active element with id 'timer' has an unsupported type. \
                The migration of a BOUNDARY_EVENT is not supported."""
                .formatted(processInstanceKey));

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("B_v1")
        .await();

    ENGINE
        .processInstance()
        .withInstanceKey(processInstanceKey)
        .migration()
        .withTargetProcessDefinitionKey(targetProcessDefinitionKey)
        .addMappingInstruction("A", "A")
        .addMappingInstruction("B_v1", "B_v2")
        .migrate();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();
    ENGINE.job().ofInstance(processInstanceKey).withType("A").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldContinueMigratedInstanceWithElementActivateBefore() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";

    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v1", s -> s.zeebeJobType("B"))
                    .endEvent("end_v1")
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .serviceTask("A", a -> a.zeebeJobType("A"))
                    .serviceTask("B_v2", s -> s.zeebeJobType("B"))
                    .endEvent("end_v2")
                    .done())
            .deploy();

    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey = ENGINE.processInstance().ofBpmnProcessId(processId).create();

    final Record<ProcessInstanceRecordValue> taskActivated =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("A")
            .getFirst();

    // when
    ENGINE.writeRecords(
        RecordToWrite.command()
            .processInstance(ProcessInstanceIntent.COMPLETE_ELEMENT, taskActivated.getValue())
            .key(taskActivated.getKey()),
        RecordToWrite.command()
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));

    final var migrationRejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(migrationRejection)
        .hasRejectionType(RejectionType.INVALID_STATE)
        .hasRejectionReason(
            createMigrationRejectionDueConcurrentModificationReason(processInstanceKey));

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementId("B_v1")
        .await();

    ENGINE
        .processInstance()
        .withInstanceKey(processInstanceKey)
        .migration()
        .withTargetProcessDefinitionKey(targetProcessDefinitionKey)
        .addMappingInstruction("B_v1", "B_v2")
        .migrate();

    ENGINE.job().ofInstance(processInstanceKey).withType("B").complete();

    // then
    assertThat(
            RecordingExporter.processInstanceRecords()
                .withProcessInstanceKey(processInstanceKey)
                .limitToProcessInstanceCompleted())
        .extracting(r -> r.getValue().getElementId(), Record::getIntent)
        .containsSubsequence(
            tuple("B_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v2", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(targetProcessId, ProcessInstanceIntent.ELEMENT_COMPLETED))
        .doesNotContain(
            tuple("B_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple("end_v1", ProcessInstanceIntent.ELEMENT_COMPLETED),
            tuple(processId, ProcessInstanceIntent.ELEMENT_COMPLETED));
  }

  @Test
  public void shouldRejectMigrationForActiveCatchEventAttachedToEventBasedGateway() {
    // given
    final String processId = helper.getBpmnProcessId();
    final String targetProcessId = helper.getBpmnProcessId() + "2";
    final var deployment =
        ENGINE
            .deployment()
            .withXmlResource(
                Bpmn.createExecutableProcess(processId)
                    .startEvent()
                    .eventBasedGateway("A")
                    .intermediateCatchEvent(
                        "MSG_1",
                        e -> e.message(m -> m.name("msg_1").zeebeCorrelationKeyExpression("key")))
                    .endEvent()
                    .moveToLastGateway()
                    .intermediateCatchEvent(
                        "MSG_2",
                        e -> e.message(m -> m.name("msg_2").zeebeCorrelationKeyExpression("key")))
                    .endEvent()
                    .done())
            .withXmlResource(
                Bpmn.createExecutableProcess(targetProcessId)
                    .startEvent()
                    .eventBasedGateway("A")
                    .intermediateCatchEvent(
                        "MSG_1",
                        e -> e.message(m -> m.name("msg_1").zeebeCorrelationKeyExpression("key")))
                    .endEvent()
                    .moveToLastGateway()
                    .intermediateCatchEvent(
                        "MSG_2",
                        e -> e.message(m -> m.name("msg_2").zeebeCorrelationKeyExpression("key")))
                    .endEvent()
                    .moveToLastGateway()
                    .intermediateCatchEvent(
                        "MSG_3",
                        e -> e.message(m -> m.name("msg_3").zeebeCorrelationKeyExpression("key")))
                    .endEvent()
                    .done())
            .deploy();
    final long targetProcessDefinitionKey =
        extractProcessDefinitionKeyByProcessId(deployment, targetProcessId);

    final var processInstanceKey =
        ENGINE.processInstance().ofBpmnProcessId(processId).withVariable("key", "key1").create();

    final var eventBasedGateway =
        RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_ACTIVATED)
            .withProcessInstanceKey(processInstanceKey)
            .withElementId("A")
            .getFirst();

    // Await until the process instance is fully subscribed to both messages
    RecordingExporter.processMessageSubscriptionRecords(ProcessMessageSubscriptionIntent.CREATED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementInstanceKey(eventBasedGateway.getKey())
        .skip(1)
        .await();

    // we generate a key for the intermediate catch event so we can activate it using events
    final var keyGenerator =
        ((MutableProcessingState) ENGINE.getProcessingState()).getKeyGenerator();
    final var intermediateCatchEventKey = keyGenerator.nextKey();

    // we need to stop the engine to ensure events are applied after writing the records directly
    ENGINE.stop();

    // when the event-based gateway is completed, the intermediate catch event is activated
    // and the migration is attempted while the intermediate catch event is active
    ENGINE.writeRecords(
        RecordToWrite.event()
            .key(eventBasedGateway.getKey())
            .processInstance(
                ProcessInstanceIntent.ELEMENT_COMPLETING, eventBasedGateway.getValue()),
        RecordToWrite.event()
            .key(eventBasedGateway.getKey())
            .processInstance(ProcessInstanceIntent.ELEMENT_COMPLETED, eventBasedGateway.getValue()),
        RecordToWrite.event()
            .key(intermediateCatchEventKey)
            .processInstance(
                ProcessInstanceIntent.ELEMENT_ACTIVATING,
                new ProcessInstanceRecord()
                    .setProcessDefinitionKey(eventBasedGateway.getValue().getProcessDefinitionKey())
                    .setBpmnProcessId(processId)
                    .setElementId("MSG_1")
                    .setBpmnElementType(BpmnElementType.INTERMEDIATE_CATCH_EVENT)
                    .setBpmnEventType(BpmnEventType.MESSAGE)
                    .setProcessInstanceKey(processInstanceKey)
                    .setFlowScopeKey(processInstanceKey)),
        RecordToWrite.event()
            .key(intermediateCatchEventKey)
            .processInstance(
                ProcessInstanceIntent.ELEMENT_ACTIVATED,
                new ProcessInstanceRecord()
                    .setProcessDefinitionKey(eventBasedGateway.getValue().getProcessDefinitionKey())
                    .setBpmnProcessId(processId)
                    .setElementId("MSG_1")
                    .setBpmnElementType(BpmnElementType.INTERMEDIATE_CATCH_EVENT)
                    .setBpmnEventType(BpmnEventType.MESSAGE)
                    .setProcessInstanceKey(processInstanceKey)
                    .setFlowScopeKey(processInstanceKey)),
        RecordToWrite.command()
            .key(processInstanceKey)
            .migration(
                new ProcessInstanceMigrationRecord()
                    .setProcessInstanceKey(processInstanceKey)
                    .setTargetProcessDefinitionKey(targetProcessDefinitionKey)
                    .addMappingInstruction(
                        new ProcessInstanceMigrationMappingInstruction()
                            .setSourceElementId("A")
                            .setTargetElementId("A"))));
    ENGINE.start();

    // then
    final var rejection =
        RecordingExporter.processInstanceMigrationRecords(ProcessInstanceMigrationIntent.MIGRATE)
            .withProcessInstanceKey(processInstanceKey)
            .onlyCommandRejections()
            .getFirst();

    assertThat(rejection).hasRejectionType(RejectionType.INVALID_STATE);
    Assertions.assertThat(rejection.getRejectionReason())
        .contains(
            String.format(
                """
                Expected to migrate process instance '%s' but active element with id 'MSG_1' \
                is an intermediate catch event attached to an event-based gateway. \
                Migrating active events attached to an event-based gateway is not possible yet.""",
                processInstanceKey));
  }

  private static String createMigrationRejectionDueConcurrentModificationReason(
      final long processInstanceKey) {
    return """
        Expected to migrate process instance '%d' \
        but a concurrent command was executed on the process instance. \
        Please retry the migration."""
        .formatted(processInstanceKey);
  }
}
