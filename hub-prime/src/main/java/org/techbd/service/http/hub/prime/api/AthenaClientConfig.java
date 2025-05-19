package org.techbd.service.http.hub.prime.api;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

@Configuration
public class AthenaClientConfig {
    @Value("${AWS_REGION:us-east-1}")
    private String region;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;
    
    @Value("${AWS_SESSION_TOKEN:}")
    private String sessionToken;

    @Bean
    public AthenaClient athenaClient() {
        if (accessKeyId == null || accessKeyId.isEmpty()) {
            throw new IllegalArgumentException("AWS_ACCESS_KEY_ID is not set");
        }
        
        if (secretAccessKey == null || secretAccessKey.isEmpty()) {
            throw new IllegalArgumentException("AWS_SECRET_ACCESS_KEY is not set");
        }

        AwsSessionCredentials credentials = AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken);
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);

        // Validate credentials before creating AthenaClient
        try {
            validateCredentials(credentialsProvider);
        } catch (Exception e) {
            throw new BeanCreationException("Failed to create AthenaClient due to invalid credentials: " + e.getMessage(), e);
        }

        return AthenaClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    private void validateCredentials(StaticCredentialsProvider credentialsProvider) {
        try (StsClient stsClient = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build()) {

            GetCallerIdentityResponse response = stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build());
            
            // If we get here, credentials are valid
            String account = response.account();
            String arn = response.arn();
            System.out.println("Successfully validated AWS credentials for account: " + account + " and ARN: " + arn);
            
        } catch (StsException e) {
            throw new IllegalStateException("AWS credentials are invalid or expired: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate AWS credentials: " + e.getMessage(), e);
        }
    }
}
