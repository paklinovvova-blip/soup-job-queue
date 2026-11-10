package dev.soup.queue.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Task 3.1. */
class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String hash(String json) throws Exception {
        return CanonicalJson.hash(mapper.readTree(json));
    }

    @Test
    void keyOrderDoesNotMatterAtAnyDepth() throws Exception {
        assertThat(hash("{\"a\":1,\"b\":{\"x\":true,\"y\":[1,2]}}"))
                .isEqualTo(hash("{\"b\":{\"y\":[1,2],\"x\":true},\"a\":1}"));
    }

    @Test
    void numbersAreComparedByValue() throws Exception {
        assertThat(hash("{\"lr\":1}")).isEqualTo(hash("{\"lr\":1.0}")).isEqualTo(hash("{\"lr\":1e0}"));
        assertThat(hash("{\"n\":0}")).isEqualTo(hash("{\"n\":0.00}"));
    }

    @Test
    void differentValuesGiveDifferentHashes() throws Exception {
        assertThat(hash("{\"lr\":1}")).isNotEqualTo(hash("{\"lr\":2}"));
        assertThat(hash("{\"lr\":\"1\"}")).isNotEqualTo(hash("{\"lr\":1}"));
        assertThat(hash("{\"a\":[1,2]}")).isNotEqualTo(hash("{\"a\":[2,1]}")); // array order is data
    }
}
