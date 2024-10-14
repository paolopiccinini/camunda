/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.schema;

import io.camunda.webapps.schema.descriptors.IndexDescriptor;
import java.util.Collection;
import java.util.Map;

public interface SchemaManager {
  void initialiseResources();

  void updateSchema(final Map<IndexDescriptor, Collection<IndexMappingProperty>> newFields);
}
