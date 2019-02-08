import React from 'react';

import CombinedReportView from './CombinedReportView';
import ProcessReportView from './ProcessReportView';
import DecisionReportView from './DecisionReportView';

import {ErrorBoundary, Message} from 'components';

import './ReportView.scss';

const errorMessage =
  'Cannot display data for the given report settings. Please choose another combination!';

export default function ReportView(props) {
  const {report} = props;
  let View;
  if (report) {
    if (report.combined) View = CombinedReportView;
    else if (report.reportType === 'decision') View = DecisionReportView;
    else View = ProcessReportView;

    return (
      <ErrorBoundary>
        <div className="ReportView">
          <View {...props} errorMessage={errorMessage} />
        </div>
      </ErrorBoundary>
    );
  } else {
    return <Message type="error">{errorMessage}</Message>;
  }
}
