package atc.common;

/** Control service -> aircraft. Optional fields are null (and omitted from JSON) when unused. */
public record Instruction(String requestId, String aircraftId, InstructionStatus status,
                          String runwayId, String taxiwayId, Long slotStart, Long slotEnd,
                          String reason)
        implements Message {
}
