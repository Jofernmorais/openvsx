/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.security;

import java.time.Instant;

import javax.persistence.EntityManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.openvsx.entities.AuthToken;
import org.eclipse.openvsx.entities.UserData;
import org.json.simple.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class TokenService {

    protected final Logger logger = LoggerFactory.getLogger(TokenService.class);

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    EntityManager entityManager;

    @Autowired
    ClientRegistrationRepository clientRegistrationRepository;

    public AuthToken updateTokens(long userId, String registrationId, OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) {
        var userData = entityManager.find(UserData.class, userId);
        if (userData == null) {
            return null;
        }
    
        switch (registrationId) {
            case "github": {
                if (accessToken == null) {
                    return transactions.execute(status -> {
                        userData.setGithubToken(null);
                        return null;
                    });
                }
                var token = new AuthToken();
                token.accessToken = accessToken.getTokenValue();
                token.scopes = accessToken.getScopes();
                token.issuedAt = accessToken.getIssuedAt();
                token.expiresAt = accessToken.getExpiresAt();
                return transactions.execute(status -> {
                    userData.setGithubToken(token);
                    return token;
                });
            }

            case "eclipse": {
                if (accessToken == null) {
                    return transactions.execute(status -> {
                        userData.setEclipseToken(null);
                        return null;
                    });
                }
                var token = new AuthToken();
                token.accessToken = accessToken.getTokenValue();
                token.scopes = accessToken.getScopes();
                token.issuedAt = accessToken.getIssuedAt();
                token.expiresAt = accessToken.getExpiresAt();
                
                if (refreshToken != null) {
                    token.refreshToken = refreshToken.getTokenValue();
                } else {
                    var tokens = refreshEclipseToken(token);
                    if (tokens != null) {
                        token.accessToken = tokens.getFirst().getTokenValue();
                        token.scopes = tokens.getFirst().getScopes();
                        token.issuedAt = tokens.getFirst().getIssuedAt();
                        token.expiresAt = tokens.getFirst().getExpiresAt();
                        token.refreshToken = tokens.getSecond().getTokenValue();
                    }
                }
                return transactions.execute(status -> {
                    userData.setEclipseToken(token);
                    return token;
                });
            }
        }
        return null;
    }

    public AuthToken getActiveToken(UserData userData, String registrationId) {
        switch (registrationId) {
            case "github": {
                return userData.getGithubToken();
            }

            case "eclipse": {
                var token = userData.getEclipseToken();
                if (token == null)
                    return null;
                if (token.expiresAt != null && Instant.now().isAfter(token.expiresAt)) {
                    var newTokens = refreshEclipseToken(token);
                    if (newTokens == null) {
                        return updateTokens(userData.getId(), "eclipse", null, null);
                    }
                    return updateTokens(userData.getId(), "eclipse", newTokens.getFirst(), newTokens.getSecond());
                }
                return token;
            }
        }

        return null;
    }

    protected Pair<OAuth2AccessToken, OAuth2RefreshToken> refreshEclipseToken(AuthToken token) {
        var reg = clientRegistrationRepository.findByRegistrationId("eclipse");
        var tokenUri = reg.getProviderDetails().getTokenUri();

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var data = new JsonObject();
        data.put("grant_type", "refresh_token");
        data.put("client_id", reg.getClientId());
        data.put("client_secret", reg.getClientSecret());
        data.put("refresh_token", token.accessToken);

        var request = new HttpEntity<String>(data.toJson(), headers);
        var restTemplate = new RestTemplate();
        var objectMapper = new ObjectMapper();
        try {
            var response = restTemplate.postForObject(tokenUri, request, String.class);
            var root = objectMapper.readTree(response);
            var newTokenValue = root.get("access_token").asText();
            var newRefreshTokenValue = root.get("refresh_token").asText();
            var expires_in = root.get("expires_in").asLong();

            var issuedAt = Instant.now();
            var expiresAt = issuedAt.plusSeconds(expires_in);

            var newToken = new OAuth2AccessToken(TokenType.BEARER, newTokenValue, issuedAt, expiresAt);
            var newRefreshToken = new OAuth2RefreshToken(newRefreshTokenValue, issuedAt);
            return Pair.of(newToken, newRefreshToken);

        } catch (RestClientException exc) {
            logger.error("Post request failed with URL: " + tokenUri, exc);
        } catch (JsonProcessingException exc) {
            logger.error("Invalid JSON data received from URL: " + tokenUri, exc);
        }
        return null;
    }

}