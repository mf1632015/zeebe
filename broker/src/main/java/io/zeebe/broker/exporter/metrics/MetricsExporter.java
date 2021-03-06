/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.exporter.metrics;

import io.zeebe.broker.system.configuration.ExporterCfg;
import io.zeebe.exporter.api.Exporter;
import io.zeebe.exporter.api.context.Controller;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.WorkflowInstanceRecordValue;
import java.time.Duration;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.agrona.collections.Long2LongHashMap;

public class MetricsExporter implements Exporter {

  public static final Duration TIME_TO_LIVE = Duration.ofSeconds(10);
  private final ExecutionLatencyMetrics executionLatencyMetrics;
  private final Long2LongHashMap jobKeyToCreationTimeMap;
  private final Long2LongHashMap workflowInstanceKeyToCreationTimeMap;

  private final NavigableMap<Long, Long> creationTimeToJobKeyNavigableMap;
  private final NavigableMap<Long, Long> creationTimeToWorkflowInstanceKeyNavigableMap;

  private Controller controller;

  public MetricsExporter() {
    this.executionLatencyMetrics = new ExecutionLatencyMetrics();
    this.jobKeyToCreationTimeMap = new Long2LongHashMap(-1);
    this.workflowInstanceKeyToCreationTimeMap = new Long2LongHashMap(-1);
    this.creationTimeToJobKeyNavigableMap = new TreeMap<>();
    this.creationTimeToWorkflowInstanceKeyNavigableMap = new TreeMap<>();
  }

  @Override
  public void open(final Controller controller) {
    this.controller = controller;

    controller.scheduleTask(TIME_TO_LIVE, this::cleanUp);
  }

  @Override
  public void close() {
    jobKeyToCreationTimeMap.clear();
    workflowInstanceKeyToCreationTimeMap.clear();
  }

  @Override
  public void export(final Record record) {
    if (record.getRecordType() != RecordType.EVENT) {
      controller.updateLastExportedRecordPosition(record.getPosition());
      return;
    }

    final var partitionId = record.getPartitionId();
    final var recordKey = record.getKey();

    final var currentValueType = record.getValueType();
    if (currentValueType == ValueType.JOB) {
      handleJobRecord(record, partitionId, recordKey);
    } else if (currentValueType == ValueType.WORKFLOW_INSTANCE) {
      handleWorkflowInstanceRecord(record, partitionId, recordKey);
    }

    controller.updateLastExportedRecordPosition(record.getPosition());
  }

  private void handleWorkflowInstanceRecord(
      final Record<?> record, final int partitionId, final long recordKey) {
    final var currentIntent = record.getIntent();

    if (currentIntent == WorkflowInstanceIntent.ELEMENT_ACTIVATING
        && isWorkflowInstanceRecord(record)) {
      storeWorkflowInstanceCreation(record.getTimestamp(), recordKey);
    } else if (currentIntent == WorkflowInstanceIntent.ELEMENT_COMPLETED
        && isWorkflowInstanceRecord(record)) {
      final var creationTime = workflowInstanceKeyToCreationTimeMap.remove(recordKey);
      executionLatencyMetrics.observeWorkflowInstanceExecutionTime(
          partitionId, creationTime, record.getTimestamp());
    }
  }

  private void storeWorkflowInstanceCreation(final long creationTime, final long recordKey) {
    workflowInstanceKeyToCreationTimeMap.put(recordKey, creationTime);
    creationTimeToWorkflowInstanceKeyNavigableMap.put(creationTime, recordKey);
  }

  private void handleJobRecord(
      final Record<?> record, final int partitionId, final long recordKey) {
    final var currentIntent = record.getIntent();

    if (currentIntent == JobIntent.CREATED) {
      storeJobCreation(record.getTimestamp(), recordKey);
    } else if (currentIntent == JobIntent.ACTIVATED) {
      final var creationTime = jobKeyToCreationTimeMap.get(recordKey);
      executionLatencyMetrics.observeJobActivationTime(
          partitionId, creationTime, record.getTimestamp());
    } else if (currentIntent == JobIntent.COMPLETED) {
      final var creationTime = jobKeyToCreationTimeMap.remove(recordKey);
      executionLatencyMetrics.observeJobLifeTime(partitionId, creationTime, record.getTimestamp());
    }
  }

  private void storeJobCreation(final long creationTime, final long recordKey) {
    jobKeyToCreationTimeMap.put(recordKey, creationTime);
    creationTimeToJobKeyNavigableMap.put(creationTime, recordKey);
  }

  private void cleanUp() {
    final var currentTimeMillis = System.currentTimeMillis();

    final var deadTime = currentTimeMillis - TIME_TO_LIVE.toMillis();
    clearMaps(deadTime, creationTimeToJobKeyNavigableMap, jobKeyToCreationTimeMap);
    clearMaps(
        deadTime,
        creationTimeToWorkflowInstanceKeyNavigableMap,
        workflowInstanceKeyToCreationTimeMap);
  }

  private void clearMaps(
      final long deadTime,
      final NavigableMap<Long, Long> timeToKeyMap,
      final Long2LongHashMap keyToTimestampMap) {
    final var outOfScopeInstances = timeToKeyMap.headMap(deadTime);

    for (final Long key : outOfScopeInstances.values()) {
      keyToTimestampMap.remove(key);
    }
    outOfScopeInstances.clear();
  }

  public static ExporterCfg defaultConfig() {
    final ExporterCfg exporterCfg = new ExporterCfg();
    exporterCfg.setClassName(MetricsExporter.class.getName());
    return exporterCfg;
  }

  public static String defaultExporterId() {
    return MetricsExporter.class.getSimpleName();
  }

  private static boolean isWorkflowInstanceRecord(final Record<?> record) {
    final var recordValue = (WorkflowInstanceRecordValue) record.getValue();
    return BpmnElementType.PROCESS == recordValue.getBpmnElementType();
  }
}
