/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */

import {rest} from 'msw';
import {mockServer} from 'modules/mock-server/node';
import {processInstanceDetailsStore} from './processInstanceDetails';
import {flowNodeSelectionStore} from './flowNodeSelection';
import {flowNodeMetaDataStore, MetaDataEntity} from './flowNodeMetaData';
import {waitFor} from 'modules/testing-library';
import {modificationsStore} from './modifications';
import {processInstanceDetailsStatisticsStore} from './processInstanceDetailsStatistics';
import {mockFetchProcessInstanceDetailStatistics} from 'modules/mocks/api/processInstances/fetchProcessInstanceDetailStatistics';
import {mockFetchProcessInstance} from 'modules/mocks/api/processInstances/fetchProcessInstance';
import {createInstance} from 'modules/testUtils';

const PROCESS_INSTANCE_ID = '2251799813689404';

const metaData: MetaDataEntity = {
  flowNodeId: 'ServiceTask_1',
  flowNodeInstanceId: '2251799813689409',
  flowNodeType: 'SERVICE_TASK',
  instanceCount: 5,
  instanceMetadata: null,
  incident: null,
  incidentCount: 0,
};

describe('stores/flowNodeMetaData', () => {
  beforeAll(async () => {
    mockFetchProcessInstance().withSuccess(
      createInstance({
        id: PROCESS_INSTANCE_ID,
        state: 'ACTIVE',
      })
    );

    await processInstanceDetailsStore.fetchProcessInstance(PROCESS_INSTANCE_ID);
  });

  afterAll(() => {
    processInstanceDetailsStore.reset();
  });

  beforeEach(() => {
    flowNodeSelectionStore.init();
  });

  afterEach(() => {
    flowNodeSelectionStore.reset();
    flowNodeMetaDataStore.reset();
    processInstanceDetailsStatisticsStore.reset();
    modificationsStore.reset();
  });

  it('should initially set meta data to null', () => {
    flowNodeMetaDataStore.init();
    expect(flowNodeMetaDataStore.state.metaData).toBe(null);
  });

  it('should fetch and set meta data', async () => {
    mockServer.use(
      rest.post(
        `/api/process-instances/${PROCESS_INSTANCE_ID}/flow-node-metadata`,
        (_, res, ctx) => res.once(ctx.json(metaData))
      )
    );

    flowNodeMetaDataStore.init();
    flowNodeSelectionStore.setSelection({
      flowNodeId: 'ServiceTask_1',
      flowNodeInstanceId: '2251799813689409',
    });

    await waitFor(() => {
      expect(flowNodeMetaDataStore.state.metaData).toEqual(metaData);
    });

    flowNodeSelectionStore.setSelection(null);

    await waitFor(() => {
      expect(flowNodeMetaDataStore.state.metaData).toEqual(null);
    });
  });

  it('should retry fetch on network reconnection', async () => {
    mockServer.use(
      rest.post(
        `/api/process-instances/${PROCESS_INSTANCE_ID}/flow-node-metadata`,
        (_, res, ctx) => res.once(ctx.json(metaData))
      )
    );

    const eventListeners: any = {};
    const originalEventListener = window.addEventListener;
    window.addEventListener = jest.fn((event: string, cb: any) => {
      eventListeners[event] = cb;
    });

    flowNodeMetaDataStore.init();
    flowNodeSelectionStore.setSelection({
      flowNodeId: 'ServiceTask_1',
      flowNodeInstanceId: '2251799813689409',
    });

    await waitFor(() => {
      expect(flowNodeMetaDataStore.state.metaData).toEqual(metaData);
    });

    const newMetaData = {
      ...metaData,
      instanceCount: 6,
    };

    mockServer.use(
      rest.post(
        `/api/process-instances/${PROCESS_INSTANCE_ID}/flow-node-metadata`,
        (_, res, ctx) => res.once(ctx.json(newMetaData))
      )
    );
    eventListeners.online();

    await waitFor(() => {
      expect(flowNodeMetaDataStore.state.metaData).toEqual(newMetaData);
    });

    window.addEventListener = originalEventListener;
  });

  it('should not fetch metadata in modification mode if flow node does not have any running/finished instances', async () => {
    mockFetchProcessInstanceDetailStatistics().withSuccess([
      {
        activityId: 'ServiceTask_1',
        active: 1,
        canceled: 0,
        incidents: 0,
        completed: 0,
      },
    ]);

    modificationsStore.enableModificationMode();
    await processInstanceDetailsStatisticsStore.fetchFlowNodeStatistics(
      PROCESS_INSTANCE_ID
    );

    flowNodeMetaDataStore.init();
    flowNodeSelectionStore.setSelection({
      flowNodeId: 'ServiceTask_2',
      flowNodeInstanceId: '2251799813689409',
    });

    expect(flowNodeMetaDataStore.state.metaData).toEqual(null);
  });
});
