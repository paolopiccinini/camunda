/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */

import React, {useEffect} from 'react';
import {incidentsByErrorStore} from 'modules/stores/incidentsByError';
import {observer} from 'mobx-react';
import {useLocation} from 'react-router-dom';
import {PartiallyExpandableDataTable} from '../PartiallyExpandableDataTable';
import {Locations} from 'modules/Routes';
import {panelStatesStore} from 'modules/stores/panelStates';
import {tracking} from 'modules/tracking';
import {getAccordionTitle} from './utils/getAccordionTitle';
import {InstancesBar} from 'modules/components/Carbon/InstancesBar';
import {truncateErrorMessage} from './utils/truncateErrorMessage';
import {Skeleton} from '../PartiallyExpandableDataTable/Skeleton';
import {LinkWrapper, ErrorMessage} from '../styled';
import {EmptyState} from 'modules/components/Carbon/EmptyState';
import {ReactComponent as EmptyStateProcessIncidents} from 'modules/components/Icon/empty-state-process-incidents.svg';
import {Details} from './Details';

const IncidentsByError: React.FC = observer(() => {
  const location = useLocation();

  useEffect(() => {
    incidentsByErrorStore.init();
    return () => {
      incidentsByErrorStore.reset();
    };
  }, []);

  useEffect(() => {
    incidentsByErrorStore.getIncidentsByError();
  }, [location.key]);

  const {incidents, status} = incidentsByErrorStore.state;

  if (['initial', 'first-fetch'].includes(status)) {
    return <Skeleton />;
  }

  if (status === 'fetched' && incidents.length === 0) {
    return (
      <EmptyState
        icon={<EmptyStateProcessIncidents title="Your processes are healthy" />}
        heading="Your processes are healthy"
        description="There are no incidents on any instances."
      />
    );
  }

  if (status === 'error') {
    return <ErrorMessage />;
  }

  return (
    <PartiallyExpandableDataTable
      dataTestId="incident-byError"
      headers={[{key: 'incident', header: 'incident'}]}
      rows={incidents.map(({errorMessage, instancesWithErrorCount}) => {
        return {
          id: errorMessage,
          incident: (
            <LinkWrapper
              to={Locations.processes({
                errorMessage: truncateErrorMessage(errorMessage),
                incidents: true,
              })}
              onClick={() => {
                panelStatesStore.expandFiltersPanel();
                tracking.track({
                  eventName: 'navigation',
                  link: 'dashboard-process-incidents-by-error-message-all-processes',
                });
              }}
              title={getAccordionTitle(instancesWithErrorCount, errorMessage)}
            >
              <InstancesBar
                label={{type: 'incident', size: 'small', text: errorMessage}}
                incidentsCount={instancesWithErrorCount}
                size="medium"
              />
            </LinkWrapper>
          ),
        };
      })}
      expandedContents={incidents.reduce(
        (accumulator, {errorMessage, processes}) => ({
          ...accumulator,
          [errorMessage]: (
            <Details errorMessage={errorMessage} processes={processes} />
          ),
        }),
        {},
      )}
    />
  );
});

export {IncidentsByError};
