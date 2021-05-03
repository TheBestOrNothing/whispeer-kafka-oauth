/*
 * Copyright 2017-2021, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.testsuite.oauth.auth;

import io.strimzi.kafka.oauth.client.ClientConfig;
import io.strimzi.kafka.oauth.common.TokenInfo;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.junit.Assert;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static io.strimzi.kafka.oauth.common.OAuthAuthenticator.loginWithClientSecret;
import static io.strimzi.testsuite.oauth.auth.Common.buildProducerConfigOAuthBearer;
import static io.strimzi.testsuite.oauth.auth.Common.buildProducerConfigPlain;

public class ErrorReportingTests {

    void doTests() throws Exception {
        unparseableJwtToken();
        corruptTokenIntrospect();
        invalidJwtTokenKid();
        forgedJwtSig();
        forgedJwtSigIntrospect();
        expiredJwtToken();
        badClientIdOAuthOverPlain();
        badSecretOAuthOverPlain();
        cantConnectPlainWithClientCredentials();
        cantConnectIntrospect();
    }

    String getKafkaBootstrap(int port) {
        return "kafka:" + (port - 100);
    }

    void commonChecks(Throwable cause) {
        Assert.assertEquals("Expected SaslAuthenticationException", SaslAuthenticationException.class, cause.getClass());
    }

    void checkErrId(String message) {
        Assert.assertTrue("Error message is sanitised", message.substring(message.length() - 16).startsWith("ErrId:"));
    }

    private void unparseableJwtToken() throws Exception {
        String token = "unparseable";

        System.out.println("==== KeycloakErrorsTest :: unparseableJwtToken ====");

        final String kafkaBootstrap = getKafkaBootstrap(9303);

        Map<String, String> oauthConfig = new HashMap<>();
        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN, token);
        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN_IS_JWT, "false");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-unparseableJwtTokenTest";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkUnparseableJwtTokenErrorMessage(cause.toString());
        }
    }

    void checkUnparseableJwtTokenErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Failed to parse JWT"));
    }

    private void corruptTokenIntrospect() throws Exception {
        String token = "corrupt";

        System.out.println("==== KeycloakErrorsTest :: corruptTokenIntrospect ====");

        final String kafkaBootstrap = getKafkaBootstrap(9302);

        Map<String, String> oauthConfig = new HashMap<>();
        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN, token);
        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN_IS_JWT, "false");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-corruptTokenIntrospect";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkCorruptTokenIntrospectErrorMessage(cause.getMessage());
        }
    }

    void checkCorruptTokenIntrospectErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Token not active"));
    }

    private void invalidJwtTokenKid() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: invalidJwtTokenKid ====");

        // We authenticate against 'demo' realm, but use it with listener configured with 'kafka-authz' realm
        final String kafkaBootstrap = getKafkaBootstrap(9303);
        final String hostPort = "keycloak:8080";

        final String tokenEndpointUri = "http://" + hostPort + "/auth/realms/demo/protocol/openid-connect/token";

        Map<String, String> oauthConfig = new HashMap<>();
        oauthConfig.put(ClientConfig.OAUTH_TOKEN_ENDPOINT_URI, tokenEndpointUri);
        oauthConfig.put(ClientConfig.OAUTH_CLIENT_ID, "kafka-producer-client");
        oauthConfig.put(ClientConfig.OAUTH_CLIENT_SECRET, "kafka-producer-client-secret");
        oauthConfig.put(ClientConfig.OAUTH_USERNAME_CLAIM, "preferred_username");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-invalidJwtTokenKidTest";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkInvalidJwtTokenKidErrorMessage(cause.getMessage());
        }
    }

    void checkInvalidJwtTokenKidErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Unknown signing key (kid:"));
    }

    private void forgedJwtSig() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: forgedJwtSig ====");

        final String kafkaBootstrap = getKafkaBootstrap(9301);
        final String hostPort = "keycloak:8080";
        final String realm = "demo-ec";

        final String tokenEndpointUri = "http://" + hostPort + "/auth/realms/" + realm + "/protocol/openid-connect/token";

        final String clientId = "kafka-producer-client";
        final String clientSecret = "kafka-producer-client-secret";

        // first, request access token using client id and secret
        TokenInfo info = loginWithClientSecret(URI.create(tokenEndpointUri), null, null, clientId, clientSecret, true, null, null);

        Map<String, String> oauthConfig = new HashMap<>();
        String tokenWithBrokenSig = info.token().substring(0, info.token().length() - 6) + "ffffff";

        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN, tokenWithBrokenSig);
        oauthConfig.put(ClientConfig.OAUTH_USERNAME_CLAIM, "preferred_username");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-forgedJwtSig";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkForgedJwtSigErrorMessage(cause.getMessage());
        }
    }

    void checkForgedJwtSigErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Invalid token signature"));
    }

    private void forgedJwtSigIntrospect() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: forgedJwtSigIntrospect ====");

        final String kafkaBootstrap = getKafkaBootstrap(9302);
        final String hostPort = "keycloak:8080";
        final String realm = "demo";

        final String tokenEndpointUri = "http://" + hostPort + "/auth/realms/" + realm + "/protocol/openid-connect/token";

        final String clientId = "kafka-producer-client";
        final String clientSecret = "kafka-producer-client-secret";

        // first, request access token using client id and secret
        TokenInfo info = loginWithClientSecret(URI.create(tokenEndpointUri), null, null, clientId, clientSecret, true, null, null);

        Map<String, String> oauthConfig = new HashMap<>();
        String tokenWithBrokenSig = info.token().substring(0, info.token().length() - 6) + "ffffff";

        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN, tokenWithBrokenSig);
        oauthConfig.put(ClientConfig.OAUTH_USERNAME_CLAIM, "preferred_username");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-forgedJwtSigIntrospect";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkForgedJwtSigIntrospectErrorMessage(cause.getMessage());
        }
    }

    void checkForgedJwtSigIntrospectErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Token not active"));
    }

    private void expiredJwtToken() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: expiredJwtToken ====");

        final String kafkaBootstrap = getKafkaBootstrap(9305);
        final String hostPort = "keycloak:8080";
        final String realm = "expiretest";

        final String tokenEndpointUri = "http://" + hostPort + "/auth/realms/" + realm + "/protocol/openid-connect/token";

        final String clientId = "kafka-producer-client";
        final String clientSecret = "kafka-producer-client-secret";

        // first, request access token using client id and secret
        TokenInfo info = loginWithClientSecret(URI.create(tokenEndpointUri), null, null, clientId, clientSecret, true, null, null);

        Map<String, String> oauthConfig = new HashMap<>();
        oauthConfig.put(ClientConfig.OAUTH_ACCESS_TOKEN, info.token());
        oauthConfig.put(ClientConfig.OAUTH_USERNAME_CLAIM, "preferred_username");

        // sleep for 6s for token to expire
        Thread.sleep(6000);

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-expiredJwtTokenTest";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkExpiredJwtTokenErrorMessage(cause.getMessage());
        }
    }

    void checkExpiredJwtTokenErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Token expired at: "));
    }

    private void badClientIdOAuthOverPlain() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: badClientIdOAuthOverPlain ====");

        final String kafkaBootstrap = getKafkaBootstrap(9304);

        Map<String, String> plainConfig = new HashMap<>();
        plainConfig.put("username", "team-a-inexistent");
        plainConfig.put("password", "team-a-client-secret");

        Properties producerProps = buildProducerConfigPlain(kafkaBootstrap, plainConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-badClientIdOAuthOverPlainTest";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkBadClientIdOAuthOverPlainErrorMessage(cause.getMessage());
        }
    }

    void checkBadClientIdOAuthOverPlainErrorMessage(String message) {
        // errId can not be propagated over PLAIN so it is not present
        Assert.assertTrue(message.contains("credentials for user could not be verified"));
    }

    private void badSecretOAuthOverPlain() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: badSecretOAuthOverPlain ====");

        final String kafkaBootstrap = getKafkaBootstrap(9304);

        Map<String, String> plainConfig = new HashMap<>();
        plainConfig.put("username", "team-a-client");
        plainConfig.put("password", "team-a-client-bad-secret");

        Properties producerProps = buildProducerConfigPlain(kafkaBootstrap, plainConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-badSecretOAuthOverPlainTest";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkBadCSecretOAuthOverPlainErrorMessage(cause.getMessage());
        }
    }

    void checkBadCSecretOAuthOverPlainErrorMessage(String message) {
        // errId can not be propagated over PLAIN so it is not present
        Assert.assertTrue(message.contains("credentials for user could not be verified"));
    }

    private void cantConnectPlainWithClientCredentials() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: cantConnectPlainWithClientCredentials ====");

        final String kafkaBootstrap = getKafkaBootstrap(9306);

        Map<String, String> plainConfig = new HashMap<>();
        plainConfig.put("username", "team-a-client");
        plainConfig.put("password", "team-a-client-secret");

        Properties producerProps = buildProducerConfigPlain(kafkaBootstrap, plainConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-cantConnectPlainWithClientCredentials";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkCantConnectPlainWithClientCredentialsErrorMessage(cause.getMessage());
        }
    }

    void checkCantConnectPlainWithClientCredentialsErrorMessage(String message) {
        // errId can not be propagated over PLAIN so it is not present
        Assert.assertTrue(message.contains("credentials for user could not be verified"));
    }

    private void cantConnectIntrospect() throws Exception {
        System.out.println("==== KeycloakErrorsTest :: cantConnectIntrospect ====");

        final String kafkaBootstrap = getKafkaBootstrap(9307);
        final String hostPort = "keycloak:8080";
        final String realm = "kafka-authz";

        final String tokenEndpointUri = "http://" + hostPort + "/auth/realms/" + realm + "/protocol/openid-connect/token";

        Map<String, String> oauthConfig = new HashMap<>();
        oauthConfig.put(ClientConfig.OAUTH_TOKEN_ENDPOINT_URI, tokenEndpointUri);
        oauthConfig.put(ClientConfig.OAUTH_CLIENT_ID, "team-a-client");
        oauthConfig.put(ClientConfig.OAUTH_CLIENT_SECRET, "team-a-client-secret");
        oauthConfig.put(ClientConfig.OAUTH_USERNAME_CLAIM, "preferred_username");

        Properties producerProps = buildProducerConfigOAuthBearer(kafkaBootstrap, oauthConfig);
        Producer<String, String> producer = new KafkaProducer<>(producerProps);

        final String topic = "KeycloakErrorsTest-cantConnectIntrospect";

        try {
            producer.send(new ProducerRecord<>(topic, "The Message")).get();
            Assert.fail("Should fail with ExecutionException");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            commonChecks(cause);
            checkCantConnectIntrospectErrorMessage(cause.getMessage());
        }
    }

    void checkCantConnectIntrospectErrorMessage(String message) {
        checkErrId(message);
        Assert.assertTrue(message.contains("Runtime failure during token validation"));
    }
}
