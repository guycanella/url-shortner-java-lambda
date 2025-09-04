package com.guycanella;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UrlShortenerHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDb = DynamoDbClient.create();
    private final String TABLE_NAME = System.getenv("TABLE_NAME");
    private final String CUSTOM_DOMAIN = System.getenv("CUSTOM_DOMAIN");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String path = input.getPath();
        String method = input.getHttpMethod();

        if (method.equalsIgnoreCase("POST")) {
            return shortenUrl(input.getBody(), context);
        } else if (method.equalsIgnoreCase("GET")) {
            String shortCode = path.substring(path.lastIndexOf('/') + 1);
            return redirectUrl(shortCode, context);
        }

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("Unsupported operation.");
    }

    private APIGatewayProxyResponseEvent shortenUrl(String body, Context context) {
        String originalUrl;
        int ttlMinutes = 1;

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String,Object> map = mapper.readValue(body, Map.class);
            originalUrl = (String) map.get("url");

            if (map.containsKey("ttl")) {
                try {
                    Object ttlObject = map.get("ttl");

                    if (ttlObject != null) {
                        ttlMinutes = Integer.parseInt(ttlObject.toString());

                        if (ttlMinutes < 1) ttlMinutes = 1;
                        if (ttlMinutes > 525600) ttlMinutes = 525600;
                    }
                } catch(Exception ex) {
                    context.getLogger().log("Invalid TTL, using default 1 minute." + ex.getMessage());
                }
            }
        } catch (Exception exception) {
            originalUrl = body;
            if (originalUrl != null && originalUrl.startsWith("\"") && originalUrl.endsWith("\"")) {
                originalUrl = originalUrl.substring(1, originalUrl.length() - 1);
            }
        }

        if (originalUrl == null || originalUrl.trim().isEmpty()) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("{ \"error\": \"URL is required.\" }");
        }

        if (!originalUrl.startsWith("https://") && !originalUrl.startsWith("http://")) {
            originalUrl = "https://" + originalUrl;
        }

        String code = CodeGenerator.generateCode();
        long now = Instant.now().getEpochSecond();
        long ttl = now + (ttlMinutes * 60L);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("shortCode", AttributeValue.builder().s(code).build());
        item.put("originalUrl", AttributeValue.builder().s(originalUrl).build());
        item.put("createdAt", AttributeValue.builder().n(String.valueOf(now)).build());
        item.put("expiresAt", AttributeValue.builder().n(String.valueOf(ttl)).build());
        item.put("clickCount", AttributeValue.builder().n("0").build());

        try {
            dynamoDb.putItem(PutItemRequest.builder().tableName(TABLE_NAME).item(item).build());
        } catch (Exception exception) {
            context.getLogger().log("Error saving at DynamoDB: " + exception.getMessage());

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{ \"error\": \"Failed to create short URL.\" }");
        }

        String shortUrl = CUSTOM_DOMAIN + "/" + code;

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("{ \"url\": \""+ shortUrl + "\" }");
    }

    private APIGatewayProxyResponseEvent redirectUrl(String shortCode, Context context) {
        Map<String,AttributeValue> key = new HashMap<>();
        key.put("shortCode", AttributeValue.builder().s(shortCode).build());

        try {
            GetItemResponse response = dynamoDb.getItem(
                    GetItemRequest.builder().tableName(TABLE_NAME).key(key).build()
            );

            if (!response.hasItem() || response.item().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                        .withStatusCode(404)
                        .withBody("URL Not Found.");
            }

            Map<String,AttributeValue> item = response.item();
            AttributeValue expiresAtAttr = item.get("expiresAt");

            if (expiresAtAttr != null && expiresAtAttr.n() != null) {
                long expiresAt = Long.parseLong(expiresAtAttr.n());

                if (Instant.now().getEpochSecond() > expiresAt) {
                    return new APIGatewayProxyResponseEvent()
                            .withStatusCode(410)
                            .withBody("URL expired");
                }
            }

            String originalUrl = item.get("originalUrl").s();

            try {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(key)
                        .updateExpression("SET clickCount = clickCount + :inc")
                        .expressionAttributeValues(Map.of(":inc",
                                AttributeValue.builder().n("1").build()))
                        .build());
            } catch (Exception exception) {
                context.getLogger().log("Fail to update clickCount: " + exception.getMessage());
            }

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(302)
                    .withHeaders(Map.of("Location", originalUrl))
                    .withBody("Redirecting to " + originalUrl);
        } catch (Exception exception) {
            context.getLogger().log("Error searching the URL: " + exception.getMessage());
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Internal server error");
        }
    }

    public static class CodeGenerator {
        private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        private static final int CODE_LENGTH = 8;
        private static final SecureRandom random = new SecureRandom();

        public static String generateCode() {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
            }

            return sb.toString();
        }
    }
}
