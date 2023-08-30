/*
 * Copyright 2017-2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.examples.producer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.strimzi.kafka.oauth.client.ClientConfig;
//import io.strimzi.kafka.oauth.common.SECP256K1;
import io.strimzi.kafka.oauth.common.End2EndEncryption;
import io.strimzi.kafka.oauth.common.Config;
import io.strimzi.kafka.oauth.common.ConfigProperties;
import io.strimzi.kafka.oauth.common.WEB3;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

import com.nimbusds.jose.jwk.ECKey;
//import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.crypto.ECDSAVerifier;
//import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.JOSEException;

import java.text.ParseException;
import java.util.Date;
import java.util.UUID;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Security;
import java.security.Provider;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.bouncycastle.jsse.BCSNIHostName;
import org.bouncycastle.jsse.util.SNIUtil;


/**
 * An example synchronous (single-threaded) producer implementation
 */
@SuppressFBWarnings("THROWS_METHOD_THROWS_RUNTIMEEXCEPTION")
public class Alice {

    /**
     * A main method
     *
     * @param args No arguments expected
     */
    public static void main(String[] args) {

        // Register BouncyCastleProvider
        BouncyCastleProvider bcProvider = new BouncyCastleProvider();

        System.out.println("bcProvider.getInfo(): " + bcProvider.getInfo());
        Package bcPackage = bcProvider.getClass().getPackage();

        // Print the artifactId and version
        System.out.println("Artifact ID: " + bcPackage.getImplementationTitle());
        System.out.println("Version: " + bcPackage.getImplementationVersion());
        System.out.println(bcPackage.getImplementationVendor());
        System.out.println(bcPackage.getName());
        System.out.println(bcPackage.getSpecificationTitle());
        System.out.println(bcPackage.getSpecificationVendor());
        System.out.println(bcPackage.getSpecificationVersion());
        
        BouncyCastleJsseProvider jsseProvider = new BouncyCastleJsseProvider();
        Package jssePackage = jsseProvider.getClass().getPackage();
        System.out.println(jssePackage.getImplementationVendor());
        System.out.println(jssePackage.getName());
        System.out.println(jssePackage.getSpecificationTitle());
        System.out.println(jssePackage.getSpecificationVendor());
        System.out.println(jssePackage.getSpecificationVersion());

        //Security.addProvider(new BouncyCastleProvider());
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(), 2);
        Provider[] providers = Security.getProviders();

        try {
            BCSNIHostName hostname = SNIUtil.getBCSNIHostName(new URL("https://www.google.com"));
        } catch (MalformedURLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        for (Provider provider : providers) {
            System.out.println("Name: " + provider.getName());
            System.out.println("Version: " + provider.toString());
            System.out.println("Info: " + provider.getInfo());
            System.out.println("-----------------------------------------");
        }

        Properties defaults = new Properties();
        Config external = new Config();
        
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream("alicebob.properties")) {
            properties.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // Retrieve values using property keys
        String alchemyProvider = properties.getProperty("alchemyProvider");
        if (!WEB3.checkProvider(alchemyProvider)) {
            System.out.println("Failure to validate API key: " + alchemyProvider);
            return;
        }

        String alicePrivateKeyStr = properties.getProperty("alicePrivate");
        String bobPublicKeyStr = properties.getProperty("bobPublic");
        
        BigInteger alicePrivateKeyBig = new BigInteger(alicePrivateKeyStr, 16);
        WEB3 alice = new WEB3(alicePrivateKeyBig);
        alice.printWeb3();
        
        BigInteger bobPublicKeyBig = new BigInteger(bobPublicKeyStr, 16);
        WEB3 bobPublic = WEB3.publicWEB3(bobPublicKeyBig);
        bobPublic.printWeb3();
        String topic = bobPublic.address;
        System.out.println("topic topic " + topic);

        final String accessToken = getAccessToken(alice);
        //System.out.println(accessToken);
        defaults.setProperty(ClientConfig.OAUTH_ACCESS_TOKEN, accessToken);
        // Resolve external configurations falling back to provided defaults
        ConfigProperties.resolveAndExportToSystemProperties(defaults);

        Properties props = buildProducerConfig(defaults);

        Producer<String, String> producer = new KafkaProducer<>(props);
        //SECP256K1 secp256k1 = new SECP256K1();
        End2EndEncryption e2ee = new End2EndEncryption(alice.nimbusdsJWK, bobPublic.nimbusdsJWK);

        for (int i = 0; ; i++) {
            try {
                String hello = UUID.randomUUID().toString();
                System.out.println(hello);
                producer.send(new ProducerRecord<>(topic, e2ee.message2Token(hello)))
                        .get();
                //System.out.println("Testing ... ... ... ... ... ...");
                //secp256k1.testECDH();
                System.out.println("Produced Message " + i);

            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while sending!");

            } catch (ExecutionException e) {
                if (e.getCause() instanceof AuthenticationException
                        || e.getCause() instanceof AuthorizationException) {
                    producer.close();
                    producer = new KafkaProducer<>(props);
                } else {
                    throw new RuntimeException("Failed to send message: " + i, e);
                }
            }

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while sleeping!");
            }
        }
    }

    private static String getAccessToken(WEB3 alice) {
        String token = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJMR25qdjVWVXdfdXFJd0QxZGtlQnlRTHY1SEVOaDk1bldTaU5oNDBZNW1BIn0.eyJleHAiOjE2ODM3MTM4NzUsImlhdCI6MTY4MzY3Nzg3NSwianRpIjoiY2U3YjRmYzItNWNkMS00ZDE5LTkyNjUtM2MzOGRhNDI0NTU0IiwiaXNzIjoiaHR0cDovL2tleWNsb2FrOjgwODAvYXV0aC9yZWFsbXMvZGVtbyIsImF1ZCI6ImthZmthIiwic3ViIjoiODQzZTA3MGEtNjA0MC00Mzk1LWI0MzAtMzllOWE5MzE4OWExIiwidHlwIjoiQmVhcmVyIiwiYXpwIjoia2Fma2EtcHJvZHVjZXItY2xpZW50Iiwic2Vzc2lvbl9zdGF0ZSI6ImI5NDJlMzE2LTllNmQtNGEwNy04MDExLWViZmY0YTg1NWQ0MiIsImFjciI6IjEiLCJyZXNvdXJjZV9hY2Nlc3MiOnsia2Fma2EiOnsicm9sZXMiOlsia2Fma2EtdG9waWM6c3VwZXJhcHBfKjpvd25lciJdfX0sInNjb3BlIjoicHJvZmlsZSBlbWFpbCIsInNpZCI6ImI5NDJlMzE2LTllNmQtNGEwNy04MDExLWViZmY0YTg1NWQ0MiIsImVtYWlsX3ZlcmlmaWVkIjpmYWxzZSwicHJlZmVycmVkX3VzZXJuYW1lIjoiYWxpY2UiLCJlbWFpbCI6ImFsaWNlQGV4YW1wbGUuY29tIn0.DBAq67KF9rfNVEy3L1nhaiHQIGXWBNlOW8QTRhNH1NTJ0DqZ_grFCLeckkVbr8BXSVWLHj39mx1ZCYU_1AIZB-0i8riRhqT1T5bgpAv2MkutmQEWd2FpiC5IVK1q8Vyw0bo2MDQInvjUQn9tB4NBNNzogDaBNRmatUD-m2y3tAJ3T-sl4fiaMXX6bzaf55r4LfwVYkP0TyeBzQoXCyUtPlP2ECKWMt6A4MTjT6ysfE7Odmk_VGploUzhSMG_BEIGcA8tFcYx4nWjV8f5PaIwpz8cmBlZBQjnfU4gnKk1U-cl6gb3EMtu9YLiqGY8ktx0P5QOm4q8h5TRd5dtuFIBHg";
        // Generate EC key pair on the secp256k1 curve
        try {
            // Parse token string into a SignedJWT object
            SignedJWT signedJWT = SignedJWT.parse(token);
            System.out.println(signedJWT.getHeader().toJSONObject());
            JWSHeader header = signedJWT.getHeader();
            
            //Create resource_access add to the jwt
            ECKey ecJWK = alice.nimbusdsJWK;

            // Get the public EC key, for recipients to validate the signatures
            ECKey ecPublicJWK = ecJWK.toPublicJWK();

            // Get JWTClaimsSet object from SignedJWT
            JWTClaimsSet jwtClaimsSet = signedJWT.getJWTClaimsSet();

            // Create a new JWTClaimsSet object with updated expiration time
            Date newExpirationTime = new Date(System.currentTimeMillis() + 3600 * 1000); // 1 hour from now
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder(jwtClaimsSet);
            builder.expirationTime(newExpirationTime);
            JWTClaimsSet newClaimsSet = builder.build();
            
            // Create JWT for ES256K alg
            SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256K)
                    .keyID(ecJWK.getKeyID())
                    .type(header.getType())
                    .jwk(ecPublicJWK)
                    .build(),
                    newClaimsSet);

            // Sign with private EC key
            ECDSASigner signer = new ECDSASigner(ecJWK);
            signer.getJCAContext().setProvider(new BouncyCastleProvider());
            jwt.sign(signer);

            // Output the JWT
            token = jwt.serialize();
            System.out.println(token);

            // Verify the ES256K signature with the public EC key
            System.out.println(jwt.verify(new ECDSAVerifier(ecPublicJWK)));

            System.out.println(jwt.getJWTClaimsSet().toJSONObject());

        } catch (JOSEException | ParseException e) {
            System.out.println("Exception Occure111");
        }

        return token;
    }

    /**
     * Build KafkaProducer properties. The specified values are defaults that can be overridden
     * through runtime system properties or env variables.
     *
     * @return Configuration properties
     */
    private static Properties buildProducerConfig(Properties p) {

        //Properties p = new Properties();

        p.setProperty("security.protocol", "SASL_SSL");
        p.setProperty("security.providers", "org.bouncycastle.jce.provider.BouncyCastleProvider,org.bouncycastle.jsse.provider.BouncyCastleJsseProvider");
        p.setProperty("sasl.mechanism", "OAUTHBEARER");
        p.setProperty("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required ;");
        p.setProperty("sasl.login.callback.handler.class", "io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler");

        p.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "ubuntu:9092");
        //p.setProperty("bootstrap.servers", "ubuntu");
        p.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        p.setProperty(ProducerConfig.ACKS_CONFIG, "all");

        // Adjust re-authentication options
        // See: strimzi-kafka-oauth/README.md
        p.setProperty("sasl.login.refresh.buffer.seconds", "300");
        p.setProperty("sasl.login.refresh.min.period.seconds", "300");
        p.setProperty("sasl.login.refresh.window.factor", "0.8");
        p.setProperty("sasl.login.refresh.window.jitter", "0.05");

        //p.setProperty("ssl.provider", "org.bouncycastle.jsse.provider.BouncyCastleJsseProvider");
        //p.setProperty("ssl.provider", "BCJSSE");
        //p.setProperty("ssl.provider", "BC");  
        p.setProperty("ssl.cipher.suites", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        p.setProperty("ssl.keymanager.algorithm", "PKIX");
        p.setProperty("ssl.trustmanager.algorithm", "PKIX");
        p.setProperty("ssl.keystore.type", "PKCS12");
        p.setProperty("ssl.truststore.type", "PKCS12");
        p.setProperty("ssl.truststore.location", "/tmp/ssl/client.truststore.jks");
        p.setProperty("ssl.truststore.location", "/tmp/ssl/client.truststore.jks");
        p.setProperty("ssl.truststore.password", "client-truststore-pass");
        p.setProperty("ssl.keystore.location", "/tmp/ssl/client.keystore.jks");
        p.setProperty("ssl.keystore.password", "client-keystore-pass");
        p.setProperty("ssl.key.password", "client-keystore-pass");
        p.setProperty("ssl.enabled.protocols", "TLSv1.2");
        p.setProperty("ssl.client.auth", "required");
        //p.setProperty("ssl.endpoint.identification.algorithm", "https");
        p.setProperty("ssl.endpoint.identification.algorithm", "https");

        return ConfigProperties.resolve(p);
    }

}

