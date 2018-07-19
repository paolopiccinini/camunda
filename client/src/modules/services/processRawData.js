import React from 'react';
import {convertCamelToSpaces} from './formatters';

export default function processRawData(
  data,
  excludedColumns = [],
  columnOrder = {processInstanceProps: [], variables: []},
  endpoints = {}
) {
  const allColumnsLength = Object.keys(data[0]).length - 1 + Object.keys(data[0].variables).length;
  // If all columns is excluded return a message to enable one
  if (allColumnsLength === excludedColumns.length)
    return {head: ['No Data'], body: [['You need to enable at least one table column']]};

  const processInstanceProps = Object.keys(data[0]).filter(
    entry => entry !== 'variables' && !excludedColumns.includes(entry)
  );
  const variableNames = Object.keys(data[0].variables).filter(
    entry => !excludedColumns.includes('var__' + entry)
  );

  function applyBehavior(type, instance) {
    const content = instance[type];
    if (type === 'processInstanceId') {
      const {endpoint, engineName} = endpoints[instance.engineName] || {};
      if (endpoint) {
        return (
          <a href={`${endpoint}/app/cockpit/${engineName}/#/process-instance/${content}`}>
            {content}
          </a>
        );
      }
    }
    return content;
  }

  const body = data.map(instance => {
    let row = processInstanceProps.map(entry => applyBehavior(entry, instance));
    const variableValues = variableNames.map(entry => {
      const value = instance.variables[entry];
      if (value === null) {
        return '';
      }
      return value.toString();
    });
    row.push(...variableValues);

    return row;
  });

  const head = processInstanceProps.map(convertCamelToSpaces);

  if (variableNames.length > 0) {
    head.push({label: 'Variables', columns: variableNames});
  }

  const {sortedHead, sortedBody} = sortColumns(head, body, columnOrder);

  return {head: sortedHead, body: sortedBody};
}

function sortColumns(head, body, columnOrder) {
  const sortedHead = sortHead(head, columnOrder);
  const sortedBody = sortBody(body, head, sortedHead);

  return {sortedHead, sortedBody};
}

function sortHead(head, columnOrder) {
  const sortedHeadWithoutVariables = head
    .filter(onlyNonNestedColumns)
    .sort(byOrder(columnOrder.processInstanceProps));

  const sortedHeadVariables = head.filter(onlyNestedColumns).map(entry => {
    return {
      ...entry,
      columns: [...entry.columns].sort(byOrder(columnOrder.variables))
    };
  });

  return sortedHeadWithoutVariables.concat(sortedHeadVariables);
}

function onlyNonNestedColumns(entry) {
  return !entry.columns;
}

function onlyNestedColumns(entry) {
  return entry.columns;
}

function byOrder(order) {
  return function(a, b) {
    return order.indexOf(a) - order.indexOf(b);
  };
}

function sortBody(body, head, sortedHead) {
  return body.map(row => sortRow(row, head, sortedHead));
}

function sortRow(row, head, sortedHead) {
  const sortedRowWithoutVariables = row
    .filter(belongingToNonNestedColumn(head))
    .map(valueForNewColumnPosition(head, sortedHead));

  const sortedRowVariables = row.filter(belongingToNestedColumn(head)).map(
    valueForNewColumnPosition(
      getNestedColumnsForLastEntry(head), // nested columns for last head entry are variables
      getNestedColumnsForLastEntry(sortedHead)
    )
  );

  return sortedRowWithoutVariables.concat(sortedRowVariables);
}

function belongingToNonNestedColumn(head) {
  return function(_, idx) {
    return head[idx] && !head[idx].columns;
  };
}

function belongingToNestedColumn(head) {
  return function(_, idx) {
    return !head[idx] || head[idx].columns;
  };
}

function valueForNewColumnPosition(head, sortedHead) {
  return function(_, newPosition, cells) {
    const headerAtNewPosition = sortedHead[newPosition];
    const originalPosition = head.indexOf(headerAtNewPosition);

    return cells[originalPosition];
  };
}

function getNestedColumnsForLastEntry(head) {
  return head[head.length - 1].columns;
}
