package atc.common;

/** A message could not be decoded or encoded. Callers reply with ERROR instead of crashing. */
public class CodecException extends Exception {
    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
