/********************************************************************************
 * Copyright (c) 2020 TypeFox and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.openvsx.eclipse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.io.IOException;
import java.io.InputStreamReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;

import org.eclipse.openvsx.MockTransactionTemplate;
import org.eclipse.openvsx.entities.AuthToken;
import org.eclipse.openvsx.entities.EclipseData;
import org.eclipse.openvsx.entities.UserData;
import org.eclipse.openvsx.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(SpringExtension.class)
public class EclipseServiceTest {

    @MockBean
    TokenService tokens;

    @MockBean
    RestTemplate restTemplate;

    @Autowired
    EclipseService eclipse;

    @BeforeEach
    public void setup() {
        eclipse.publisherAgreementVersion = "1";
        eclipse.publisherAgreementApiUrl = "https://test.openvsx.eclipse.org/";
        eclipse.publisherAgreementTimeZone = "US/Eastern";
    }

    @Test
    public void testGetPublisherAgreement() throws Exception {
        var user = mockUser();
        var eclipseData = new EclipseData();
        user.setEclipseData(eclipseData);
        eclipseData.personId = "test";

        Mockito.when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(PublisherAgreementResponse.class)))
            .thenReturn(new ResponseEntity<>(mockAgreementResponse(), HttpStatus.OK));

        var agreement = eclipse.getPublisherAgreement(user);

        assertThat(agreement).isNotNull();
        assertThat(agreement.isActive).isTrue();
        assertThat(agreement.documentId).isEqualTo("abcd");
        assertThat(agreement.version).isEqualTo("1");
        assertThat(agreement.timestamp).isNotNull();
        assertThat(agreement.timestamp.toString()).isEqualTo("2020-10-09T09:10:32");
    }

    @Test
    public void testGetPublisherAgreementNotFound() throws Exception {
        var user = mockUser();
        var eclipseData = new EclipseData();
        user.setEclipseData(eclipseData);
        eclipseData.personId = "test";

        Mockito.when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(PublisherAgreementResponse.class)))
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        var agreement = eclipse.getPublisherAgreement(user);

        assertThat(agreement).isNull();
    }

    @Test
    public void testGetPublisherAgreementNotAuthenticated() throws Exception {
        var user = mockUser();

        var agreement = eclipse.getPublisherAgreement(user);

        assertThat(agreement).isNull();
    }

    @Test
    public void testSignPublisherAgreement() throws Exception {
        var user = mockUser();
        Mockito.when(restTemplate.postForObject(any(String.class), any(), eq(PublisherAgreementResponse.class)))
            .thenReturn(mockAgreementResponse());

        eclipse.signPublisherAgreement(user);

        assertThat(user.getEclipseData()).isNotNull();
        var ed = user.getEclipseData();
        assertThat(ed.personId).isEqualTo("test");
        assertThat(ed.publisherAgreement).isNotNull();
        assertThat(ed.publisherAgreement.isActive).isTrue();
        assertThat(ed.publisherAgreement.documentId).isEqualTo("abcd");
        assertThat(ed.publisherAgreement.version).isEqualTo("1");
        assertThat(ed.publisherAgreement.timestamp).isNotNull();
        assertThat(ed.publisherAgreement.timestamp.toString()).isEqualTo("2020-10-09T09:10:32");
    }

    @Test
    public void testRevokePublisherAgreement() throws Exception {
        var user = mockUser();
        var eclipseData = new EclipseData();
        user.setEclipseData(eclipseData);
        eclipseData.personId = "test";
        eclipseData.publisherAgreement = new EclipseData.PublisherAgreement();
        eclipseData.publisherAgreement.isActive = true;

        eclipse.revokePublisherAgreement(user);

        assertThat(user.getEclipseData().publisherAgreement.isActive).isFalse();
    }

    private UserData mockUser() {
        var user = new UserData();
        user.setLoginName("test");
        user.setEclipseToken(new AuthToken());
        user.getEclipseToken().accessToken = "12345";
        Mockito.when(tokens.getActiveToken(user, "eclipse"))
            .thenReturn(user.getEclipseToken());
        return user;
    }

    private PublisherAgreementResponse mockAgreementResponse() throws IOException {
        try (
            var stream = getClass().getResourceAsStream("publisher-agreement-response.json");
        ) {
            var json = CharStreams.toString(new InputStreamReader(stream));
            return new ObjectMapper().readValue(json, PublisherAgreementResponse.class);
        }
    }
    
    @TestConfiguration
    static class TestConfig {
        @Bean
        TransactionTemplate transactionTemplate() {
            return new MockTransactionTemplate();
        }

        @Bean
        EclipseService eclipseService() {
            return new EclipseService();
        }
    }
    
}