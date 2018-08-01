import React from 'react';

import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import Viewer from 'bpmn-js/lib/Viewer';

import {withErrorHandling} from 'HOC';

import './BPMNDiagram.css';

const availableViewers = [];

export default withErrorHandling(
  class BPMNDiagram extends React.Component {
    state = {
      loaded: false
    };

    storeContainer = container => {
      this.container = container;
    };

    render() {
      return (
        <div className="BPMNDiagram" style={this.props.style} ref={this.storeContainer}>
          {this.state.loaded &&
            this.props.children &&
            React.Children.map(this.props.children, child =>
              React.cloneElement(child, {viewer: this.viewer})
            )}
        </div>
      );
    }

    componentDidUpdate(prevProps) {
      if (prevProps.xml !== this.props.xml) {
        this.unattach(prevProps.xml);
        this.importXML(this.props.xml);
      }
    }

    unattach = xml => {
      if (this.viewer) {
        this.viewer.detach();
        availableViewers.push({
          viewer: this.viewer,
          disableNavigation: this.props.disableNavigation,
          xml
        });
      }
    };

    findOrCreateViewerFor = xml => {
      const idx = availableViewers.findIndex(
        conf => conf.xml === xml && conf.disableNavigation === this.props.disableNavigation
      );

      const available = availableViewers[idx];

      if (available) {
        availableViewers.splice(idx, 1);
        return available.viewer;
      }

      const viewer = new (this.props.disableNavigation ? Viewer : NavigatedViewer)({
        canvas: {
          deferUpdate: false
        }
      });

      return new Promise(resolve => {
        viewer.importXML(xml, () => resolve(viewer));
      });
    };

    importXML = xml => {
      this.setState({loaded: false});

      this.props.mightFail(this.findOrCreateViewerFor(xml), viewer => {
        this.viewer = viewer;
        this.viewer.attachTo(this.container);

        this.fitDiagram();

        this.setState({loaded: true});
      });
    };

    componentDidMount() {
      this.importXML(this.props.xml);

      const dashboardObject = this.container.closest('.DashboardObject');
      if (dashboardObject) {
        // if the diagram is on a dashboard, react to changes of the dashboard objects size
        new MutationObserver(this.fitDiagram).observe(dashboardObject, {attributes: true});
      }
    }

    componentWillUnmount() {
      this.unattach(this.props.xml);
    }

    fitDiagram = () => {
      const canvas = this.viewer.get('canvas');

      canvas.resized();
      canvas.zoom('fit-viewport', 'auto');
    };
  }
);
