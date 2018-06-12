import styled from 'styled-components';

import {Colors, themed, themeStyle} from 'theme';

export const Checkbox = themed(styled.input`
  width: 14px;
  height: 14px;
  border-radius: 3px;
  /* background: ${themeStyle({
    dark: Colors.uiDark02,
    light: Colors.uiLight06
  })}; */
  border: solid 1px #bebec0;
  cursor: pointer;
`);

export const Label = themed(styled.span`
  margin-left: 11px;
  opacity: 0.9;
  font-size: 13px;
  color: ${themeStyle({
    dark: '#ececec',
    light: Colors.uiLight06
  })};
`);
