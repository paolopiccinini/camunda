/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */

import {screen, within} from '@testing-library/testcafe';
import {demoUser} from './utils/Roles';
import {config} from '../config';
import {setup} from './Operations.setup';
import {DATE_REGEX} from './constants';
import {displayOptionalFilter} from './utils/displayOptionalFilter';
import {processesPage as ProcessesPage} from './PageModels/Processes';
import {processInstancePage as ProcessInstancePage} from './PageModels/ProcessInstance';

fixture('Operations')
  .page(config.legacyEndpoint)
  .before(async (ctx) => {
    ctx.initialData = await setup();
  })
  .beforeEach(async (t) => {
    await t
      .useRole(demoUser)
      .maximizeWindow()
      .click(
        screen.queryByRole('link', {
          name: /processes/i,
        }),
      );
  });

test('infinite scrolling', async (t) => {
  await t.click(screen.queryByTitle('Expand Operations'));

  await t.expect(screen.getAllByTestId('operations-entry').count).eql(20);

  await t.scrollIntoView(screen.getAllByTestId('operations-entry').nth(19));

  await t.expect(screen.getAllByTestId('operations-entry').count).eql(40);
});

test('Retry and Cancel single instance ', async (t) => {
  const {initialData} = t.fixtureCtx;
  const instance = initialData.singleOperationInstance;

  // ensure page is loaded
  await t.expect(screen.queryByTestId('data-list').exists).ok();

  await displayOptionalFilter('Process Instance Key(s)');

  // filter by Process Instance Key
  await ProcessesPage.typeText(
    ProcessesPage.Filters.instanceIds.field,
    instance.processInstanceKey,
    {
      paste: true,
    },
  );

  // wait for filter to be applied
  await t.expect(screen.queryByText('1 results found').exists).ok();

  // retry single instance using operation button
  await t.click(
    screen.queryByRole('button', {
      name: `Retry Instance ${instance.processInstanceKey}`,
    }),
  );

  // expect spinner to show and disappear
  await t.expect(ProcessInstancePage.operationSpinner.exists).ok();
  await t.expect(ProcessInstancePage.operationSpinner.exists).notOk();

  // cancel single instance using operation button
  await t
    .click(
      screen.queryByRole('button', {
        name: `Cancel Instance ${instance.processInstanceKey}`,
      }),
    )
    .click(screen.getByRole('button', {name: 'Apply'}));

  await t
    .expect(screen.queryByTestId('operations-list').visible)
    .notOk()
    .click(screen.queryByRole('button', {name: 'Expand Operations'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok();

  const operationItem = within(screen.queryByTestId('operations-list'))
    .getAllByRole('listitem')
    .nth(0);
  const operationId = await within(operationItem).queryByTestId('operation-id')
    .innerText;

  await t
    .expect(within(operationItem).queryByText('Cancel').exists)
    .ok()
    .expect(within(operationItem).queryByText(DATE_REGEX).exists)
    .ok()
    .expect(
      screen.queryByText('There are no Instances matching this filter set')
        .exists,
    )
    .ok();

  await t.click(within(operationItem).queryByText('1 Instance'));

  await t
    .expect(ProcessesPage.Filters.operationId.value.value)
    .eql(operationId)
    .expect(screen.queryByText('1 results found').exists)
    .ok();

  const instanceRow = within(
    within(screen.queryByTestId('data-list')).getAllByRole('row').nth(0),
  );

  await t
    .expect(
      screen.queryByTestId(`CANCELED-icon-${instance.processInstanceKey}`)
        .exists,
    )
    .ok()
    .expect(instanceRow.queryByText(instance.bpmnProcessId).exists)
    .ok()
    .expect(instanceRow.queryByText(instance.processInstanceKey).exists)
    .ok();
});

test('Retry and cancel multiple instances ', async (t) => {
  const {initialData} = t.fixtureCtx;

  const instances = initialData.batchOperationInstances.slice(0, 5);

  // ensure page is loaded
  await t.expect(screen.queryByTestId('data-list').exists).ok();

  const instancesListItems = within(
    screen.queryByTestId('operations-list'),
  ).getAllByRole('listitem');

  await displayOptionalFilter('Process Instance Key(s)');

  // filter by Process Instance Keys
  await ProcessesPage.typeText(
    ProcessesPage.Filters.instanceIds.field,
    // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
    instances.map((instance) => instance.processInstanceKey).join(','),
    {paste: true},
  );

  const instancesList = screen.queryByTestId('data-list');

  // wait for the filter to be applied
  await t
    .expect(within(instancesList).getAllByRole('row').count)
    .eql(instances.length);

  await t.click(ProcessesPage.selectAllInstancesCheckbox);

  await t.click(
    screen.queryByRole('button', {
      name: `Apply Operation on ${instances.length} Instances...`,
    }),
  );

  await t
    .click(
      within(screen.queryByTestId('menu')).queryByRole('button', {
        name: 'Retry',
      }),
    )
    .expect(screen.queryByTestId('operations-list').visible)
    .notOk()
    .click(screen.queryByRole('button', {name: 'Apply'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok()
    .expect(screen.getAllByTitle(/has scheduled operations/i).count)
    .eql(instances.length);

  // expect first operation item to have progress bar
  await t
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists,
    )
    .ok();

  // wait for instance to finish in operation list (end time is present, progess bar gone)
  await t
    .expect(within(instancesListItems.nth(0)).queryByText(DATE_REGEX).exists)
    .ok()
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists,
    )
    .notOk();

  // reset filters
  await t
    .click(ProcessesPage.resetFiltersButton)
    .expect(within(instancesList).getAllByRole('row').count)
    .gt(instances.length);

  // select all instances from operation
  await t
    .click(
      within(instancesListItems.nth(0)).queryByText(
        `${instances.length} Instances`,
      ),
    )
    .expect(within(instancesList).getAllByRole('row').count)
    .eql(instances.length)
    .expect(ProcessesPage.Filters.operationId.value.value)
    .eql(
      await within(instancesListItems.nth(0)).queryByTestId('operation-id')
        .innerText,
    );

  // check if all instances are shown
  await Promise.all(
    instances.map(
      // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
      async (instance) =>
        await t
          .expect(
            within(instancesList).queryByText(instance.processInstanceKey)
              .exists,
          )
          .ok(),
    ),
  );

  await t.click(ProcessesPage.selectAllInstancesCheckbox);

  await t.click(
    screen.queryByRole('button', {
      name: `Apply Operation on ${instances.length} Instances...`,
    }),
  );

  await t
    .click(
      within(screen.queryByTestId('menu')).queryByRole('button', {
        name: 'Cancel',
      }),
    )
    .click(screen.queryByRole('button', {name: 'Apply'}))
    .expect(screen.queryByTestId('operations-list').visible)
    .ok()
    .expect(screen.getAllByTitle(/has scheduled operations/i).count)
    .eql(instances.length);

  // expect first operation item to have progress bar
  await t
    .expect(
      within(instancesListItems.nth(0)).queryByTestId('progress-bar').exists,
    )
    .ok();

  // expect cancel icon to show for each instance
  await Promise.all(
    // @ts-ignore I had to use ignore instead of expect-error here because Testcafe would not run the tests with it
    instances.map(async (instance) =>
      t
        .expect(
          screen.queryByTestId(`CANCELED-icon-${instance.processInstanceKey}`)
            .exists,
        )
        .ok({timeout: 30000}),
    ),
  );
});
