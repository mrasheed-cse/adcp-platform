package com.adcpplatform.trafficking.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class StringListConverterTest {
    private final StringListConverter c = new StringListConverter();

    @Test
    void roundTrip() {
        assertEquals("BD,IN", c.convertToDatabaseColumn(List.of("BD", "IN")));
        assertEquals(List.of("BD", "IN"), c.convertToEntityAttribute("BD, IN,"));
        assertEquals(List.of(), c.convertToEntityAttribute(""));
        assertEquals(List.of(), c.convertToEntityAttribute(null));
        assertEquals("", c.convertToDatabaseColumn(null));
    }
}
