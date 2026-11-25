package atc.common;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Marker for every message on the wire. The JSON field {@code type} names the concrete
 * message, e.g. {"type":"RESERVE", ...}. Adding a message = add a record + a line here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PositionUpdate.class, name = "POSITION_UPDATE"),
        @JsonSubTypes.Type(value = RunwayRequest.class, name = "RUNWAY_REQUEST"),
        @JsonSubTypes.Type(value = Instruction.class, name = "INSTRUCTION"),
        @JsonSubTypes.Type(value = Ack.class, name = "ACK"),
        @JsonSubTypes.Type(value = Complete.class, name = "COMPLETE"),
        @JsonSubTypes.Type(value = Reserve.class, name = "RESERVE"),
        @JsonSubTypes.Type(value = Confirm.class, name = "CONFIRM"),
        @JsonSubTypes.Type(value = Release.class, name = "RELEASE"),
        @JsonSubTypes.Type(value = Query.class, name = "QUERY"),
        @JsonSubTypes.Type(value = Reservation.class, name = "RESERVATION"),
        @JsonSubTypes.Type(value = Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = Event.class, name = "EVENT"),
        @JsonSubTypes.Type(value = ErrorMessage.class, name = "ERROR")
})
public interface Message {
}
