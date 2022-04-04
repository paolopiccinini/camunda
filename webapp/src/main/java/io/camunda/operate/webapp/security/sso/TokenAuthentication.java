/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

package io.camunda.operate.webapp.security.sso;

import static org.springframework.beans.factory.config.BeanDefinition.SCOPE_PROTOTYPE;

import com.auth0.client.auth.AuthAPI;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.net.TokenRequest;
import io.camunda.operate.property.OperateProperties;
import io.camunda.operate.webapp.security.OperateProfileService;
import io.camunda.operate.webapp.security.Permission;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;

@Profile(OperateProfileService.SSO_AUTH_PROFILE)
@Component
@Scope(SCOPE_PROTOTYPE)
public class TokenAuthentication extends AbstractAuthenticationToken {

  private transient Logger logger = LoggerFactory.getLogger(this.getClass());

  @Value("${" + OperateProperties.PREFIX + ".auth0.claimName}")
  private String claimName;

  @Value("${" + OperateProperties.PREFIX + ".cloud.organizationid"+"}")
  private String organization;

  @Value("${" + OperateProperties.PREFIX + ".auth0.backendDomain}")
  private String domain;

  @Value("${" + OperateProperties.PREFIX + ".auth0.clientId}")
  private String clientId;

  @Value("${" + OperateProperties.PREFIX + ".auth0.clientSecret}")
  private String clientSecret;
  private String idToken;
  private String refreshToken;
  private List<Permission> permissions = new ArrayList<>();

  //private List<Permission> permissions = new ArrayList<>();

  public TokenAuthentication() {
    super(null);
  }

  private boolean isIdEqualsOrganization(final Map<String, String> orgs) {
    return orgs.containsKey("id") && orgs.get("id").equals(organization);
  }

  // Need this because this class will be serialized in session
  private Logger getLogger(){
    if (logger == null) {
      logger = LoggerFactory.getLogger(this.getClass());
    }
    return logger;
  }

  @Override
  public boolean isAuthenticated() {
    if (hasExpired()) {
      getLogger().info("Access token is expired");
      if (refreshToken == null) {
        setAuthenticated(false);
        getLogger().info("No refresh token available. Authentication is invalid.");
      } else {
        getLogger().info("Get a new access token by using refresh token");
        getNewTokenByRefreshToken();
      }
    }
    return super.isAuthenticated();
  }

  public List<Permission> getPermissions() {
    return permissions;
  }

  private void getNewTokenByRefreshToken() {
    try {
      final TokenRequest tokenRequest = getAuthAPI().renewAuth(refreshToken);
      final TokenHolder tokenHolder = tokenRequest.execute();
      authenticate(tokenHolder.getIdToken(), tokenHolder.getRefreshToken());
      getLogger().info("New access token received and validated.");
    } catch (Auth0Exception e) {
      getLogger().error(e.getMessage(), e.getCause());
      setAuthenticated(false);
    }
  }

  private AuthAPI getAuthAPI() {
    return new AuthAPI(domain, clientId, clientSecret);
  }

  public boolean hasExpired() {
    final Date expires = JWT.decode(idToken).getExpiresAt();
    return expires == null || expires.before(new Date());
  }

  public Date getExpiresAt() {
    return JWT.decode(idToken).getExpiresAt();
  }

  @Override
  public String getCredentials() {
    return JWT.decode(idToken).getToken();
  }

  @Override
  public Object getPrincipal() {
    return JWT.decode(idToken).getSubject();
  }

  public void authenticate(final String idToken, final String refreshToken) {
    this.idToken = idToken;
    // Normally the refresh token will be issued only once
    // after first successfully getting the access token
    // ,so we need to avoid that the refreshToken will be overridden with null
    if (refreshToken != null) {
      this.refreshToken = refreshToken;
    }
    Claim claim = JWT.decode(idToken).getClaim(claimName);
    tryAuthenticateAsListOfMaps(claim);
    if (!isAuthenticated()) {
      throw new InsufficientAuthenticationException(
          "No permission for operate - check your organization id");
    }
  }

  private void tryAuthenticateAsListOfMaps(final Claim claim) {
    try {
      List<? extends Map> claims = claim.asList(Map.class);
      if (claims != null) {
        setAuthenticated(claims.stream().anyMatch(this::isIdEqualsOrganization));
      }
    } catch (JWTDecodeException e) {
      getLogger().debug("Read organization claim as list of maps failed.", e);
    }
  }

  /**
   * Gets the claims for this JWT token. <br> For an ID token, claims represent user profile
   * information such as the user's name, profile, picture, etc. <br>
   *
   * @return a Map containing the claims of the token.
   * @see <a href="https://auth0.com/docs/tokens/id-token">ID Token
   * Documentation</a>
   */
  public Map<String, Claim> getClaims() {
    return JWT.decode(idToken).getClaims();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final TokenAuthentication that = (TokenAuthentication) o;
    return claimName.equals(that.claimName) && organization.equals(that.organization)
        && domain.equals(that.domain) && clientId.equals(that.clientId) && clientSecret.equals(
        that.clientSecret) && idToken.equals(that.idToken) && Objects.equals(refreshToken,
        that.refreshToken);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), claimName, organization, domain, clientId, clientSecret,
        idToken, refreshToken);
  }
}
