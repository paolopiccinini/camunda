import React from 'react';
import {Redirect, Link} from 'react-router-dom';
import PropTypes from 'prop-types';

import Dropdown from 'modules/components/Dropdown';
import * as api from 'modules/api/header';
import withSharedState from 'modules/components/withSharedState';
import {fetchWorkflowInstancesCount} from 'modules/api/instances';
import {getFilterQueryString} from 'modules/utils/filter';
import {BADGE_TYPE, FILTER_SELECTION} from 'modules/constants';

import {filtersMap, localStateKeys, apiKeys} from './constants';
import * as Styled from './styled.js';

class Header extends React.Component {
  static propTypes = {
    active: PropTypes.oneOf(['dashboard', 'instances']),
    runningInstancesCount: PropTypes.number,
    filterCount: PropTypes.number,
    selectionCount: PropTypes.number,
    instancesInSelectionsCount: PropTypes.number,
    incidentsCount: PropTypes.number,
    detail: PropTypes.element,
    getStateLocally: PropTypes.func.isRequired
  };

  state = {
    forceRedirect: false,
    user: {},
    runningInstancesCount: 0,
    filterCount: 0,
    selectionCount: 0,
    instancesInSelectionsCount: 0,
    incidentsCount: 0
  };

  componentDidMount = async () => {
    this.setUser();
    localStateKeys.forEach(this.setCountFromPropsOrLocalState);
    apiKeys.forEach(this.setCountFromPropsOrApi);
  };

  componentDidUpdate = prevProps => {
    localStateKeys.forEach(key => {
      if (this.props[key] !== prevProps[key]) {
        this.setCountFromPropsOrLocalState(key);
      }
    });

    apiKeys.forEach(key => {
      if (this.props[key] !== prevProps[key]) {
        this.setCountFromPropsOrApi(key);
      }
    });
  };

  setUser = async () => {
    const user = await api.fetchUser();
    this.setState({user});
  };

  /**
   * Sets value in the state by getting it from the props or falling back to the local storage.
   * @param {string} key: key for which to set the value
   */
  setCountFromPropsOrLocalState = key => {
    const localState = this.props.getStateLocally();

    let countValue = this.props[key];

    // If the value is not provided in the props, get it from the localState.
    if (typeof countValue === 'undefined') {
      countValue = localState[key] || 0;
    }

    this.setState({[key]: countValue});
  };

  /**
   * Sets value in the state by getting it from the props or falling back to the api.
   * @param {string} key: key for which to set the value
   */
  setCountFromPropsOrApi = async key => {
    let countValue = this.props[key];

    // if it's not provided in props, fetch it from the api
    if (typeof countValue === 'undefined') {
      countValue = await fetchWorkflowInstancesCount(filtersMap[key]);
    }

    this.setState({[key]: countValue});
  };

  handleLogout = async () => {
    await api.logout();
    this.setState({forceRedirect: true});
  };

  render() {
    const {active, detail} = this.props;
    const {firstname, lastname} = this.state.user || {};

    // query for the incidents link
    const incidentsQuery = getFilterQueryString(FILTER_SELECTION.incidents);

    return this.state.forceRedirect ? (
      <Redirect to="/login" />
    ) : (
      <Styled.Header>
        <Styled.Menu role="navigation">
          <li>
            <Styled.Dashboard active={active === 'dashboard'}>
              <Link to="/">
                <Styled.LogoIcon />
                <span>Dashboard</span>
              </Link>
            </Styled.Dashboard>
          </li>
          <li data-test="instances">
            <Styled.ListLink active={this.props.active === 'instances'}>
              <Link
                to="/instances"
                title={`${this.state.runningInstancesCount} Instances`}
              >
                <span>Instances</span>
                <Styled.Badge
                  type="instances"
                  badgeContent={this.state.runningInstancesCount}
                />
              </Link>
            </Styled.ListLink>
          </li>
          <li data-test="filters">
            <Styled.ListLink active={this.props.active === 'instances'}>
              <Link to="/instances" title={`${this.state.filterCount} Filters`}>
                <span>Filters</span>
                <Styled.Badge
                  type="filters"
                  badgeContent={this.state.filterCount}
                />
              </Link>
            </Styled.ListLink>
          </li>
          <li data-test="selections">
            <Styled.ListLink active={this.props.active === 'instances'}>
              <Link
                to="/instances"
                title={`${this.state.instancesInSelectionsCount} ${
                  this.state.selectionCount
                } Selections`}
              >
                <span>Selections</span>
                <Styled.SelectionBadge
                  type={BADGE_TYPE.COMBOSELECTION}
                  badgeContent={this.state.instancesInSelectionsCount}
                  circleContent={this.state.selectionCount}
                />
              </Link>
            </Styled.ListLink>
          </li>
          <li data-test="incidents">
            <Styled.ListLink active={this.props.active === 'instances'}>
              <Link
                to={`/instances${incidentsQuery}`}
                title={`${this.state.incidentsCount} Incidents`}
              >
                <span>Incidents</span>
                <Styled.Badge
                  type="incidents"
                  badgeContent={this.state.incidentsCount}
                />
              </Link>
            </Styled.ListLink>
          </li>
        </Styled.Menu>

        <Styled.Detail>{detail}</Styled.Detail>
        <Styled.ProfileDropdown>
          <Dropdown label={`${firstname} ${lastname}`}>
            <Dropdown.Option
              label="Logout"
              data-test="logout-button"
              onClick={this.handleLogout}
            />
          </Dropdown>
        </Styled.ProfileDropdown>
      </Styled.Header>
    );
  }
}

export default withSharedState(Header);
