package com.jobradar.apply.internal;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gmail API over plain HTTP. Exchanges the user's refresh token for a short-lived access token
 * and calls {@code users.messages.send}. The only scope the user granted for this is
 * {@code gmail.send}; nothing here reads the mailbox.
 *
 * <p>Tokens and message bodies are never logged.
 */
@Component
@EnableConfigurationProperties({GoogleProperties.class, ApplyProperties.class})
class HttpGmailSender implements GmailSender {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final TokenSource tokens;
    private final GoogleProperties properties;
    private final JsonMapper json;

    HttpGmailSender(TokenSource tokens, GoogleProperties properties, JsonMapper json) {
        this.tokens = tokens;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void send(String tokenRef, OutgoingEmail email) {
        if (properties.clientId().isBlank() || properties.clientSecret().isBlank()) {
            throw new GmailNotConnectedException("The Google OAuth client is not configured");
        }
        String accessToken = accessToken(tokens.refreshToken(tokenRef));
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(toMime(email));
        HttpResponse<String> response = post(
                properties.gmailUrl() + "/users/me/messages/send",
                "application/json",
                json.writeValueAsString(Map.of("raw", raw)),
                accessToken);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new GmailNotConnectedException("Gmail refused the request");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gmail answered " + response.statusCode());
        }
    }

    private String accessToken(String refreshToken) {
        String form = "client_id=" + enc(properties.clientId())
                + "&client_secret=" + enc(properties.clientSecret())
                + "&refresh_token=" + enc(refreshToken)
                + "&grant_type=refresh_token";
        HttpResponse<String> response = post(properties.tokenUrl(), "application/x-www-form-urlencoded", form, null);
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            // invalid_grant: the user revoked access or the token expired.
            throw new GmailNotConnectedException("Google rejected the refresh token");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Google token endpoint answered " + response.statusCode());
        }
        String token = json.readTree(response.body()).path("access_token").asString("");
        if (token.isBlank()) {
            throw new IllegalStateException("Google returned no access token");
        }
        return token;
    }

    private HttpResponse<String> post(String url, String contentType, String body, String bearer) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Google is unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Google", e);
        }
    }

    static byte[] toMime(OutgoingEmail email) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            message.setFrom(new InternetAddress(email.from()));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(email.to()));
            message.setSubject(email.subject(), "UTF-8");

            MimeBodyPart text = new MimeBodyPart();
            text.setText(email.body(), "UTF-8");
            MimeBodyPart attachment = new MimeBodyPart();
            attachment.setDataHandler(new jakarta.activation.DataHandler(
                    new ByteArrayDataSource(email.attachment(), "application/pdf")));
            attachment.setFileName(email.attachmentName());

            MimeMultipart parts = new MimeMultipart("mixed");
            parts.addBodyPart(text);
            parts.addBodyPart(attachment);
            message.setContent(parts);
            message.saveChanges();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return out.toByteArray();
        } catch (jakarta.mail.MessagingException | IOException e) {
            throw new IllegalStateException("Could not build the email", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
