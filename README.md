# 🔗 URL Shortener - Java + Serverless with AWS

This project is a **serverless URL shortener** built with **Java 21** and **AWS Lambda**, using **Amazon DynamoDB** for persistence and **Amazon API Gateway** with a **custom domain** (`https://api.shortco.online`) for routing.

The system provides:
- Create shortened URLs with **dynamic TTL (expiration in minutes)**.
- Automatic redirection via HTTP 302.
- Click tracking.
- Expiration validation.
- Custom domain with **SSL (AWS Certificate Manager)**.

---

## 🚀 Architecture

### Flow:
1. The user sends a `POST /` request to the API Gateway with the URL to shorten.
2. API Gateway invokes the **Lambda function**.
3. Lambda generates a *shortCode*, calculates the **TTL**, and stores the record in **DynamoDB**.
4. A `GET /{shortCode}` request returns a `302 Redirect` to the original URL (if still valid).
5. Click counter is incremented.

---

## 🛠️ Technologies

### **AWS**
- **AWS Lambda** → serverless compute for Java.
- **Amazon DynamoDB** → NoSQL database for URL mappings.
- **Amazon API Gateway (REST API)** → HTTP interface and routing.
- **AWS Certificate Manager (ACM)** → SSL/TLS certificate.
- **Route 53 / GoDaddy DNS** → domain management.
- **CloudWatch** → logging and monitoring.

### **Java**
- **Java 21** or higher (on AWS Lambda runtime).
- **AWS SDK v2** for DynamoDB integration.
- **Jackson** for JSON parsing.

### **Dependencies (Maven)**
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb</artifactId>
    <version>2.21.0</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-core</artifactId>
    <version>2.15.2</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.2</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-annotations</artifactId>
    <version>2.15.2</version>
</dependency>
```

## ⚙️ DynamoDB Table

**Table name:** `ShortUrls`

| Field        | Type   | Description                                |
|--------------|--------|--------------------------------------------|
| `shortCode`  | String | 🔑 Primary Key — unique shortened code      |
| `originalUrl`| String | The original full URL                      |
| `createdAt`  | Number | Epoch timestamp of creation                 |
| `expiresAt`  | Number | Epoch timestamp of expiration               |
| `clickCount` | Number | Number of times the shortened URL was used  |

---

## 📌 Endpoints

### 🔹 Create shortened URL
**`POST /`**

**Request (JSON):**
```json
{ "url": "https://www.nike.com.br", "ttl": 30 }
```
* url: URL to shorten
* ttl: expiration time in minutes (optional, default = 1)

**Response:**
```json
{
  "url": "https://api.shortco.online/AbC123xy"
}
```

**`GET /{shortCode}`**

**Example**

`GET https://api.shortco.online/AbC123xy`

**Response:**
* HTTP 302 Found
* Header: Location: https://www.nike.com.br

## ▶️ Running the Project

### 1. Build
```shell
mvn clean package shade:shade
```
This generates a shaded JAR in `target/`.

### 2. Deploy Lambda

* Runtime: Java 21
* Handler: `com.guycanella.UrlShortenerHandler::handleRequest`
* Upload JAR file to AWS Lambda

### 3. Create DynamoDB Table
```shell
aws dynamodb create-table \
    --table-name <TABLE_NAME> \
    --attribute-definitions AttributeName=shortCode,AttributeType=S \
    --key-schema AttributeName=shortCode,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST
```

### 4. Configure API Gateway

**Custom variables:**
* CUSTOM_DOMAIN
* TALBE_NAME

## 🧪 Testing

**Create link (default 1min):**
```shell
curl -X POST "https://api.shortco.online/" \
     -H "Content-Type: application/json" \
     -d '{"url":"https://www.nike.com.br"}'
```

**Create link (30min):**
```shell
curl -X POST "https://api.shortco.online/" \
     -H "Content-Type: application/json" \
     -d '{"url":"https://www.nike.com.br","ttl":30}'
```

**Access shortened link:**
```shell
curl -v https://api.shortco.online/AbC123xy
```

Obs: or you can open in a browser.

## 👨‍💻 Author
Project developed in Java and in Serverless AWS Architecture.