package atc.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON encoding for all messages and log records.
 *
 * Bytes are always UTF-8, never the platform default (which is Cp1252 on some team machines),
 * so every process agrees on the wire format regardless of where it runs.
 */
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Omit null optional fields (e.g. runwayId on a REJECTED reply).
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Tolerate extra fields so a newer sender doesn't break an older receiver.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String encode(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Our own records always serialize; failure here is a programming error.
            throw new IllegalStateException("cannot encode " + value, e);
        }
    }

    public static byte[] encodeBytes(Object value) {
        return encode(value).getBytes(StandardCharsets.UTF_8);
    }

    public static Message decode(String json) throws CodecException {
        return decode(json, Message.class);
    }

    public static Message decode(byte[] bytes) throws CodecException {
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }

    public static <T> T decode(String json, Class<T> type) throws CodecException {
        if (json == null || json.isBlank()) {
            throw new CodecException("empty message", null);
        }
        try {
            T value = MAPPER.readValue(json, type);
            if (value == null) {
                throw new CodecException("message decoded to null", null);
            }
            return value;
        } catch (IOException e) {
            throw new CodecException("malformed message: " + originalMessage(e), e);
        }
    }

    /** Jackson exceptions carry the original message only for JsonProcessingException. */
    private static String originalMessage(IOException e) {
        return e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage();
    }
}
