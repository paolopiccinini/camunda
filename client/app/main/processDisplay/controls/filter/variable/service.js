import {dispatchAction} from 'view-utils';
import {get} from 'http';
import {createLoadingVariablesAction, createLoadingVariablesResultAction, createLoadingVariablesErrorAction,
        createSelectVariableIdxAction, createSetOperatorAction, createSetValueAction} from './reducer';
import {createCreateVariableFilterAction} from './routeReducer';
import {addNotification} from 'notifications';
import {dispatch} from '../store';
import {isUnique} from 'utils';

export function loadVariables(definition) {
  dispatchAction(createLoadingVariablesAction());
  get(`/api/process-definition/${definition}/variables`)
    .then(response => response.json())
    .then(result => result.sort((a, b) => a.name < b.name ? -1 : 1))
    .then(result => {
      const variableNames = result.map(variable => variable.name);

      return result.map(variable => {
        if (isUnique(variable.name, variableNames)) {
          return {
            ...variable,
            unambiguousName: variable.name
          };
        } else {
          return {
            ...variable,
            unambiguousName: variable.name + ' (' + variable.type + ')'
          };
        }
      });
    })
    .then(result => {
      dispatchAction(createLoadingVariablesResultAction(result));
    })
    .catch(err => {
      addNotification({
        status: 'Could not load variables data',
        isError: true
      });
      dispatchAction(createLoadingVariablesErrorAction(err));
    });
}

export function createVariableFilter(filter) {
  dispatch(createCreateVariableFilterAction(filter));
}

export function selectVariableIdx(idx) {
  dispatchAction(createSelectVariableIdxAction(idx));
}

export function deselectVariableIdx() {
  return selectVariableIdx(undefined);
}

export function setOperator(operator) {
  dispatchAction(createSetOperatorAction(operator));
}

export function setValue(value) {
  dispatchAction(createSetValueAction(value));
}
