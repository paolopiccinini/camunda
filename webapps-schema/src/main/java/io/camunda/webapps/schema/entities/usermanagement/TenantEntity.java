/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.webapps.schema.entities.usermanagement;

import io.camunda.webapps.schema.entities.AbstractExporterEntity;

public class TenantEntity extends AbstractExporterEntity<TenantEntity> {

  private Long key;
  private String tenantId;
  private String name;
  private String description;
  private Long memberKey;
  private String memberId;

  private EntityJoinRelation<String> join;

  public Long getKey() {
    return key;
  }

  public TenantEntity setKey(final Long key) {
    this.key = key;
    return this;
  }

  public String getTenantId() {
    return tenantId;
  }

  public TenantEntity setTenantId(final String tenantId) {
    this.tenantId = tenantId;
    return this;
  }

  public String getName() {
    return name;
  }

  public TenantEntity setName(final String name) {
    this.name = name;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public TenantEntity setDescription(final String description) {
    this.description = description;
    return this;
  }

  public Long getMemberKey() {
    return memberKey;
  }

  public TenantEntity setMemberKey(final Long memberKey) {
    this.memberKey = memberKey;
    return this;
  }

  public EntityJoinRelation<String> getJoin() {
    return join;
  }

  public TenantEntity setJoin(final EntityJoinRelation<String> join) {
    this.join = join;
    return this;
  }

  public TenantEntity setMemberId(final String memberId) {
    this.memberId = memberId;
    return this;
  }

  public static String getChildKey(final String tenantId, final String memberId) {
    return String.format("%s-%s", tenantId, memberId);
  }
}
