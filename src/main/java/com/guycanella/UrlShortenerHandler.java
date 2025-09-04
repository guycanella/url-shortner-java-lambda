package com.guycanella;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class UrlShortenerHandler implements
        RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDb = DynamoDbClient.create();
    private final String TABLE_NAME = "ShortUrls";
    private final String CUSTOM_DOMAIN = "https://api.shortco.online";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        String path = input.getPath();
        String method = input.getHttpMethod();

        if (method.equalsIgnoreCase("POST") && path.contains("shortner")) {
            return shortenUrl(input.getBody());
        } else if (method.equalsIgnoreCase("GET")) {
            String shortCode = path.substring(path.lastIndexOf('/') + 1);
            return redirectUrl(shortCode);
        }

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("Unsupported operation.");
    }

    private APIGatewayProxyResponseEvent shortenUrl(String originalUrl) {
        String code = UUID.randomUUID().toString().substring(0, 6);

        long now = Instant.now().getEpochSecond();
        int expires_1min = 60;
//        int expires_24hour = (60 * 60 * 24);
        long ttl = now + expires_1min;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("shortCode", AttributeValue.builder().s(code).build());
        item.put("originalUrl", AttributeValue.builder().s(originalUrl).build());
        item.put("createdAt", AttributeValue.builder().s(String.valueOf(now)).build());
        item.put("expiresAt", AttributeValue.builder().s(String.valueOf(ttl)).build());
        item.put("clickCount", AttributeValue.builder().s("0").build());

        dynamoDb.putItem(PutItemRequest.builder().tableName(TABLE_NAME).item(item).build());

        String shortUrl = CUSTOM_DOMAIN + "/" + code;

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("Shortened URL: "+ shortUrl);
    }

    private APIGatewayProxyResponseEvent redirectUrl(String shortCode) {
        Map<String,AttributeValue> key = new HashMap<>();
        key.put("shortCode", AttributeValue.builder().s(shortCode).build());

        GetItemResponse response = dynamoDb.getItem(
                GetItemRequest.builder().tableName(TABLE_NAME).key(key).build()
        );

        if (!response.hasItem()) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(404)
                    .withBody("URL Not Found.");
        }

        Map<String,AttributeValue> item = response.item();
        long expiresAt = Long.parseLong(item.get("expiresAt").n());

        if (Instant.now().getEpochSecond() > expiresAt) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(410)
                    .withBody("URL expired");
        }

        String originalUrl = item.get("originalUrl").s();

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(key)
                .updateExpression("SET clickCount = clickCount + :inc")
                .expressionAttributeValues(Map.of(":inc",
                        AttributeValue.builder().n("1").build()))
                .build());

        return new APIGatewayProxyResponseEvent()
                .withStatusCode(302)
                .withHeaders(Map.of("Location", originalUrl))
                .withBody("Redirecting to " + originalUrl);
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
