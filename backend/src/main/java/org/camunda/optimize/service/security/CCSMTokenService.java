/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.security;

import io.camunda.identity.sdk.Identity;
import io.camunda.identity.sdk.IdentityConfiguration;
import io.camunda.identity.sdk.authentication.AccessToken;
import io.camunda.identity.sdk.authentication.Authentication;
import io.camunda.identity.sdk.authentication.Tokens;
import io.camunda.identity.sdk.authentication.dto.AuthCodeDto;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.service.util.configuration.condition.CCSMCondition;
import org.camunda.optimize.service.util.configuration.security.CCSMAuthConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import javax.ws.rs.NotAuthorizedException;
import java.net.URI;
import java.util.List;

import static org.camunda.optimize.rest.constants.RestConstants.AUTH_COOKIE_TOKEN_VALUE_PREFIX;

@AllArgsConstructor
@Component
@Slf4j
@Conditional(CCSMCondition.class)
public class CCSMTokenService {

  // In Identity, Optimize requires users to have write access to everything
  private static final String OPTIMIZE_PERMISSION = "write:*";

  private final AuthCookieService authCookieService;
  private final ConfigurationService configurationService;

  @Bean
  private Identity identity() {
    return new Identity(identityConfiguration());
  }

  public List<String> createOptimizeAuthCookies(final Tokens tokens, final AccessToken accessToken, final String scheme) {
    final String optimizeAuthCookie = authCookieService.createNewOptimizeAuthCookie(
      accessToken.getToken().getToken(),
      accessToken.getToken().getExpiresAtAsInstant(),
      scheme
    );
    final String optimizeRefreshCookie = authCookieService.createNewOptimizeRefreshCookie(
      tokens.getRefreshToken(),
      authentication().decodeJWT(tokens.getRefreshToken()).getExpiresAt().toInstant(),
      scheme
    );
    return List.of(optimizeAuthCookie, optimizeRefreshCookie);
  }

  public List<String> createOptimizeDeleteAuthCookies() {
    return List.of(
      authCookieService.createDeleteOptimizeAuthCookie(true).toString(),
      authCookieService.createDeleteOptimizeRefreshCookie(true).toString()
    );
  }

  public Tokens exchangeAuthCode(final AuthCodeDto authCode, final String redirectUri) {
    return authentication().exchangeAuthCode(authCode, redirectUri);
  }

  public URI buildAuthorizeUri(final String redirectUri) {
    return authentication().authorizeUriBuilder(redirectUri).build();
  }

  public AccessToken verifyToken(final String accessToken) {
    final AccessToken verifiedToken = authentication().verifyToken(extractTokenFromAuthorizationValue(accessToken));
    if (!userHasOptimizeAuthorization(verifiedToken)) {
      throw new NotAuthorizedException("User is not authorized to access Optimize");
    }
    return verifiedToken;
  }

  public Tokens renewToken(final String refreshToken) {
    return authentication().renewToken(extractTokenFromAuthorizationValue(refreshToken));
  }

  public void revokeToken(final String refreshToken) {
    authentication().revokeToken(extractTokenFromAuthorizationValue(refreshToken));
  }

  public String getSubjectFromToken(final String accessToken) {
    return authentication().decodeJWT(extractTokenFromAuthorizationValue(accessToken)).getSubject();
  }

  private String extractTokenFromAuthorizationValue(final String cookieValue) {
    if (cookieValue.startsWith(AUTH_COOKIE_TOKEN_VALUE_PREFIX)) {
      return cookieValue.substring(AUTH_COOKIE_TOKEN_VALUE_PREFIX.length()).trim();
    }
    return cookieValue;
  }

  private Authentication authentication() {
    return identity().authentication();
  }

  private IdentityConfiguration identityConfiguration() {
    final CCSMAuthConfiguration ccsmAuthConfig = configurationService.getAuthConfiguration().getCcsmAuthConfiguration();
    return new IdentityConfiguration(
      ccsmAuthConfig.getIssuerUrl(), ccsmAuthConfig.getIssuerBackendUrl(),
      ccsmAuthConfig.getClientId(), ccsmAuthConfig.getClientSecret(), ccsmAuthConfig.getAudience()
    );
  }

  private static boolean userHasOptimizeAuthorization(final AccessToken accessToken) {
    return accessToken.getPermissions().contains(OPTIMIZE_PERMISSION);
  }

}
